package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.collector.PayrollCollector;
import com.renhe.di.collect.collector.PayrollDetailCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiPayroll;
import com.renhe.di.store.entity.DiPayrollDetail;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiPayrollDetailService;
import com.renhe.di.store.service.DiPayrollService;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工资同步任务（主表按月全量 + 明细按 salaryId 一致性补采）
 *
 * <pre>
 * Phase A — 探针获取月份列表
 *   page=1,size=1 → globalTotal
 *   最后一页 → 最旧 payMonth → 生成 [oldestPayMonth, 当前月] 月份区间
 *
 * Phase B — 逐月处理
 *   对每个月：
 *     1. 主表快照检查：本地数量 >= 第三方月总量 → 主表跳过
 *     2. 主表未完成 → collectAll(payMonth) → 清洗批量 upsert → 保存主表月快照
 *     3. 明细一致性检查（实时 API，覆盖补数据场景）：
 *        a. 取本月所有 salaryId（从本地主表查，保证主表已入库）
 *        b. 逐个 salaryId：API total vs 本地 count
 *        c. 不一致 → 全量重拉该 salaryId 的明细并 upsert
 *        d. 保存明细月快照（salaryId 级别汇总）
 *
 * Phase C — 全局一致性快照
 *   主表 + 明细各一条 saveSnapshot
 *
 * 补数据覆盖：
 *   明细每次同步都实时调 API 比对总量，不依赖快照时间，
 *   第三方补录数据后下次同步自动检测并补采。
 * </pre>
 */
@Slf4j
@Component
public class PayrollSyncJob extends AbstractSyncJob {

    @Autowired
    private PayrollCollector payrollCollector;

    @Autowired
    private PayrollDetailCollector payrollDetailCollector;

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private DiPayrollService payrollService;

    @Autowired
    private DiPayrollDetailService payrollDetailService;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private ProjectSyncExecutor projectSyncExecutor;

    @Autowired
    private BatchInsertService batchInsertService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    private static final int PAGE_SIZE = 100;

    // =========================================================
    // 外部调用入口（签名与原代码完全一致）
    // =========================================================

    /** 支持手动触发，由 ProjectDataSyncPipeline 统一编排，不单独参与定时调度 */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() { return "PAYROLL"; }

    @Override
    protected String getSyncType() { return "FULL"; }

    @Override
    protected String getTaskName() { return "工资全量同步"; }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 20, this::syncSingleProject);
    }

    /** Pipeline 直接调用此 public 方法，签名不可变 */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(
                project.getSourceProjectNum(),
                project.getAccount(),
                project.getPassword()
        );
    }

    // =========================================================
    // 核心同步逻辑
    // =========================================================

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        int globalThirdPartyTotal = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A：探针获取月份列表 =====
        // A1：page=1 size=1 → globalTotal
        try {
            JSONObject probe = apiClient.getPayrollPage(
                    projectNum, 1, 1, "", account, password);
            if (probe != null) {
                globalThirdPartyTotal = probe.getInt("total", 0);
            }
            log.info("项目【{}】工资全量总量：{}", projectNum, globalThirdPartyTotal);
        } catch (Exception e) {
            log.error("项目【{}】工资探针失败: {}", projectNum, e.getMessage());
            if (handleTokenExpired(e, account)) {
                return SyncResult.of(0, 0, 0, 0);
            }
            if (isAntiCrawlerMessage(e)) {
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
            return SyncResult.of(0, 0, 1, 0);
        }

        if (globalThirdPartyTotal == 0) {
            log.info("项目【{}】第三方无工资数据，跳过", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // A2：最后一页 → 最旧 payMonth → 生成月份列表
        List<String> salaryMonths = buildMonthList(projectNum, account, password, globalThirdPartyTotal);
        if (salaryMonths.isEmpty()) {
            log.warn("项目【{}】无法获取工资月份列表，跳过", projectNum);
            return SyncResult.of(0, 0, 1, 0);
        }
        log.info("项目【{}】待处理工资月份 {} 个：{}", projectNum, salaryMonths.size(), salaryMonths);

        // ===== Phase B：逐月处理 =====
        for (String month : salaryMonths) {

            // --- B1：主表快照检查 ---
            int monthThirdPartyTotal = 0;
            boolean mainTableComplete = false;
            try {
                DiSyncSnapshot snap = syncSnapshotService.getMonthSnapshot(
                        projectNum, "PAYROLL", month);
                if (snap != null
                        && snap.getMonthThirdPartyTotal() != null
                        && snap.getMonthThirdPartyTotal() > 0) {

                    long localCount = payrollService.lambdaQuery()
                            .eq(DiPayroll::getSourceProjectNum, projectNum)
                            .eq(DiPayroll::getSalaryMonth, month)
                            .eq(DiPayroll::getDeleted, 0)
                            .count();

                    if (localCount >= snap.getMonthThirdPartyTotal()) {
                        log.info("项目【{}】月份【{}】主表已完全同步（本地 {} >= 第三方 {}），跳过主表采集",
                                projectNum, month, localCount, snap.getMonthThirdPartyTotal());
                        mainTableComplete = true;
                        monthThirdPartyTotal = snap.getMonthThirdPartyTotal();
                    }
                }
            } catch (Exception e) {
                log.warn("项目【{}】查询月份【{}】主表快照失败，继续采集: {}",
                        projectNum, month, e.getMessage());
            }

            // --- B2：主表采集 ---
            List<String> monthSalaryIds = new ArrayList<>();

            if (!mainTableComplete) {
                log.info("项目【{}】开始采集月份【{}】主表", projectNum, month);

                CollectContext ctx = CollectContext.builder()
                        .sourceProjectNum(projectNum)
                        .account(account)
                        .password(password)
                        .build();
                ctx.putExtraParam("payMonth", month);

                try {
                    List<JSONObject> payrollList = payrollCollector.collectAll(ctx);

                    if (ctx.getExtraParam("_thirdPartyTotal") != null) {
                        monthThirdPartyTotal = Integer.parseInt(
                                ctx.getExtraParam("_thirdPartyTotal").toString());
                    }

                    if (payrollList == null || payrollList.isEmpty()) {
                        log.info("项目【{}】月份【{}】主表无数据", projectNum, month);
                    } else {
                        CleanContext cleanCtx = CleanContext.builder()
                                .sourceProjectNum(projectNum)
                                .dataType("PAYROLL")
                                .build();

                        List<DiPayroll> batch = new ArrayList<>();
                        for (JSONObject raw : payrollList) {
                            totalCount++;
                            String salaryId = raw.getStr("id");
                            try {
                                DiPayroll payroll = cleanPipeline.execute(raw, cleanCtx, "PAYROLL");
                                if (payroll != null) {
                                    batch.add(payroll);
                                    monthSalaryIds.add(salaryId);
                                } else {
                                    skipCount++;
                                }
                            } catch (Exception e) {
                                failCount++;
                                log.error("项目【{}】月份【{}】主表清洗失败: id={}",
                                        projectNum, month, salaryId, e);
                            }
                        }

                        if (!batch.isEmpty()) {
                            int batchSuccess = batchInsertService.batchInsertOrUpdate(
                                    batch, payrollService, 500);
                            successCount += batchSuccess;
                            failCount += (batch.size() - batchSuccess);

                            for (DiPayroll payroll : batch) {
                                dataChangePublisher.publish(
                                        "PAYROLL", projectNum, payroll.getSalaryId(), "CREATE");
                            }
                            log.info("项目【{}】月份【{}】主表入库 {} 条", projectNum, month, batchSuccess);
                        }

                        // 保存主表月份快照
                        if (monthThirdPartyTotal > 0) {
                            safeUpdateMonthSnapshot(projectNum, "PAYROLL", month,
                                    LocalDateTime.now(), monthThirdPartyTotal);
                        }
                    }
                } catch (Exception e) {
                    log.error("项目【{}】月份【{}】主表采集失败: {}", projectNum, month, e.getMessage());
                    if (handleTokenExpired(e, account)) {
                        break;
                    }
                    if (isAntiCrawlerMessage(e)) {
                        antiCrawlerTriggered = true;
                        break;
                    }
                    // 主表失败跳过该月明细，继续下一月
                    continue;
                }
            }

            // 主表已完成但 salaryId 列表未从采集流程填充，从本地数据库补充
            if (monthSalaryIds.isEmpty()) {
                monthSalaryIds = payrollService.lambdaQuery()
                        .eq(DiPayroll::getSourceProjectNum, projectNum)
                        .eq(DiPayroll::getSalaryMonth, month)
                        .eq(DiPayroll::getDeleted, 0)
                        .list()
                        .stream()
                        .map(DiPayroll::getSalaryId)
                        .collect(Collectors.toList());
            }

            if (monthSalaryIds.isEmpty()) {
                log.info("项目【{}】月份【{}】无 salaryId，跳过明细", projectNum, month);
                continue;
            }

            // --- B3：明细一致性补采 ---
            log.info("项目【{}】月份【{}】开始明细一致性检查，salaryId 数量: {}",
                    projectNum, month, monthSalaryIds.size());

            boolean detailAntiCrawler = syncPayrollDetailForMonth(
                    projectNum, month, monthSalaryIds, account, password);

            if (detailAntiCrawler) {
                antiCrawlerTriggered = true;
                break;
            }

            // 月份间短暂延迟
            sleepQuietly(500);
        }

        log.info("项目【{}】工资同步{}：总计 {} 条，成功 {} 条，失败 {} 条，跳过 {} 条",
                projectNum, antiCrawlerTriggered ? "风控中断" : "完成",
                totalCount, successCount, failCount, skipCount);

        // ===== Phase C：全局一致性快照 =====
        try {
            long localPayrollTotal = payrollService.lambdaQuery()
                    .eq(DiPayroll::getSourceProjectNum, projectNum)
                    .eq(DiPayroll::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(
                    projectNum, "PAYROLL", globalThirdPartyTotal, (int) localPayrollTotal);

            long localDetailTotal = payrollDetailService.lambdaQuery()
                    .eq(DiPayrollDetail::getSourceProjectNum, projectNum)
                    .eq(DiPayrollDetail::getDeleted, 0)
                    .count();
            // 明细全局快照的第三方总量用本地总量近似（明细无全局 total 接口）
            syncSnapshotService.saveSnapshot(
                    projectNum, "PAYROLL_DETAIL", (int) localDetailTotal, (int) localDetailTotal);
        } catch (Exception e) {
            log.error("项目【{}】工资全局快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    // =========================================================
    // 明细一致性补采
    // =========================================================

    /**
     * 对本月所有 salaryId 逐个做明细一致性检查，不一致则全量重拉。
     *
     * @return true 表示触发风控，需要中断后续月份
     */
    private boolean syncPayrollDetailForMonth(String projectNum, String month,
                                              List<String> salaryIds,
                                              String account, String password) {
        int detailThirdTotal = 0;
        int detailLocalTotal = 0;
        boolean antiCrawler = false;

        for (String salaryId : salaryIds) {
            // 实时查 API 该 salaryId 的明细总量
            int apiDetailTotal = 0;
            try {
                JSONObject probe = apiClient.getPayrollDetailPage(
                        salaryId, 1, 1, account, password);
                if (probe != null) {
                    apiDetailTotal = probe.getInt("total", 0);
                }
            } catch (Exception e) {
                log.error("项目【{}】月份【{}】salaryId={} 明细探针失败: {}",
                        projectNum, month, salaryId, e.getMessage());
                if (handleTokenExpired(e, account)) {
                    antiCrawler = true;
                    break;
                }
                if (isAntiCrawlerMessage(e)) {
                    antiCrawler = true;
                    break;
                }
                continue;
            }

            // 本地该 salaryId 的明细数量
            long localCount = payrollDetailService.lambdaQuery()
                    .eq(DiPayrollDetail::getSalaryId, salaryId)
                    .eq(DiPayrollDetail::getDeleted, 0)
                    .count();

            detailThirdTotal += apiDetailTotal;
            detailLocalTotal += (int) localCount;

            if (localCount >= apiDetailTotal) {
                log.debug("项目【{}】salaryId={} 明细一致（本地 {} >= 第三方 {}），跳过",
                        projectNum, salaryId, localCount, apiDetailTotal);
                continue;
            }

            log.info("项目【{}】salaryId={} 明细不一致（本地 {} < 第三方 {}），开始补采",
                    projectNum, salaryId, localCount, apiDetailTotal);

            // 全量重拉该 salaryId 的明细（数据量小，直接 collectAll）
            boolean detailSuccess = collectAndSaveDetail(projectNum, salaryId, account, password);
            if (!detailSuccess) {
                // collectAndSaveDetail 内部已打日志，此处检查风控标记
                // 若风控则中断
                log.warn("项目【{}】salaryId={} 明细采集失败，继续下一个", projectNum, salaryId);
            }

            // 每个 salaryId 明细采集后短暂间隔
            sleepQuietly(200 + ThreadLocalRandom.current().nextInt(300));
        }

        // 保存本月明细汇总快照
        if (detailThirdTotal > 0) {
            safeUpdateMonthSnapshot(projectNum, "PAYROLL_DETAIL", month,
                    LocalDateTime.now(), detailThirdTotal);
            log.info("项目【{}】月份【{}】明细快照：第三方 {}，本地 {}",
                    projectNum, month, detailThirdTotal, detailLocalTotal);
        }

        return antiCrawler;
    }

    /**
     * 采集单个 salaryId 的全量明细并入库。
     *
     * @return true 表示采集成功（含部分成功），false 表示完全失败
     */
    private boolean collectAndSaveDetail(String projectNum, String salaryId,
                                         String account, String password) {
        CollectContext detailCtx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .build();
        detailCtx.putExtraParam("salaryId", salaryId);

        try {
            List<JSONObject> detailList = payrollDetailCollector.collectAll(detailCtx);
            if (detailList == null || detailList.isEmpty()) {
                log.info("项目【{}】salaryId={} 第三方无明细数据", projectNum, salaryId);
                return true;
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("PAYROLL_DETAIL")
                    .build();

            List<DiPayrollDetail> batch = new ArrayList<>();
            for (JSONObject raw : detailList) {
                try {
                    DiPayrollDetail detail = cleanPipeline.execute(raw, cleanCtx, "PAYROLL_DETAIL");
                    if (detail != null) {
                        batch.add(detail);
                    }
                } catch (Exception e) {
                    log.error("项目【{}】salaryId={} 明细清洗失败: id={}",
                            projectNum, salaryId, raw.getStr("id"), e);
                }
            }

            if (!batch.isEmpty()) {
                int saved = batchInsertService.batchInsertOrUpdate(batch, payrollDetailService, 200);
                log.info("项目【{}】salaryId={} 明细入库 {} 条（共 {} 条）",
                        projectNum, salaryId, saved, batch.size());
                for (DiPayrollDetail detail : batch) {
                    dataChangePublisher.publish(
                            "PAYROLL_DETAIL", projectNum, detail.getDetailId(), "CREATE");
                }
            }
            return true;

        } catch (Exception e) {
            log.error("项目【{}】salaryId={} 明细采集异常: {}", projectNum, salaryId, e.getMessage(), e);
            return false;
        }
    }

    // =========================================================
    // 月份列表构建
    // =========================================================

    /**
     * 通过最后一页取最旧 payMonth，生成完整月份列表。
     * 失败时回退到分页扫描全量月份。
     */
    private List<String> buildMonthList(String projectNum, String account,
                                        String password, int total) {
        int lastPageNum = (int) Math.ceil((double) total / PAGE_SIZE);
        String oldestPayMonth = null;

        try {
            JSONObject lastPage = apiClient.getPayrollPage(
                    projectNum, lastPageNum, PAGE_SIZE, "", account, password);
            if (lastPage != null) {
                JSONArray list = lastPage.getJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String pm = list.getJSONObject(i).getStr("payMonth");
                        if (pm != null && !pm.isEmpty()) {
                            if (oldestPayMonth == null || pm.compareTo(oldestPayMonth) < 0) {
                                oldestPayMonth = pm;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("项目【{}】获取工资最后一页失败: {}", projectNum, e.getMessage());
        }

        if (oldestPayMonth != null) {
            try {
                List<String> months = new ArrayList<>();
                YearMonth start = YearMonth.parse(oldestPayMonth);
                YearMonth end = YearMonth.now();
                YearMonth cur = start;
                while (!cur.isAfter(end)) {
                    months.add(cur.toString());
                    cur = cur.plusMonths(1);
                }
                log.info("项目【{}】从最后一页取到最旧月份【{}】，生成 {} 个月份",
                        projectNum, oldestPayMonth, months.size());
                return months;
            } catch (Exception e) {
                log.warn("项目【{}】解析最旧月份失败，回退到分页扫描: {}", projectNum, e.getMessage());
            }
        }

        // 回退：分页扫描收集所有 payMonth
        return scanAllPayMonths(projectNum, account, password);
    }

    /**
     * 分页扫描全部工资记录，收集所有不重复的 payMonth（回退方案）。
     */
    private List<String> scanAllPayMonths(String projectNum, String account, String password) {
        java.util.LinkedHashSet<String> monthSet = new java.util.LinkedHashSet<>();
        int pageNum = 1;
        try {
            while (true) {
                JSONObject data = apiClient.getPayrollPage(
                        projectNum, pageNum, PAGE_SIZE, "", account, password);
                if (data == null) break;

                JSONArray list = data.getJSONArray("list");
                if (list == null || list.isEmpty()) break;

                for (int i = 0; i < list.size(); i++) {
                    String pm = list.getJSONObject(i).getStr("payMonth");
                    if (pm != null && !pm.isEmpty()) {
                        monthSet.add(pm);
                    }
                }

                int total = data.getInt("total", 0);
                int respPageSize = data.getInt("pageSize", PAGE_SIZE);
                if ((long) pageNum * respPageSize >= total) break;
                pageNum++;

                sleepQuietly(1000 + ThreadLocalRandom.current().nextInt(2000));
            }
        } catch (Exception e) {
            log.error("项目【{}】分页扫描工资月份失败: {}", projectNum, e.getMessage(), e);
        }
        return new ArrayList<>(monthSet);
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private void safeUpdateMonthSnapshot(String projectNum, String dataType,
                                         String monthId, LocalDateTime syncDate, int total) {
        try {
            syncSnapshotService.saveMonthSnapshot(projectNum, dataType, monthId, syncDate, total);
            log.debug("项目【{}】{}月份【{}】快照更新：total={}", projectNum, dataType, monthId, total);
        } catch (Exception e) {
            log.error("项目【{}】{}月份【{}】快照更新失败（主流程不受影响）: {}",
                    projectNum, dataType, monthId, e.getMessage());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
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
import com.renhe.di.store.entity.DiPayroll;
import com.renhe.di.store.entity.DiPayrollDetail;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiPayrollDetailService;
import com.renhe.di.store.service.DiPayrollService;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 工资同步任务（全量）
 * 按月份分页同步工资主表+明细
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

    /**
     * 支持手动触发或独立调度工资信息同步
     * 默认不参与定时调度，由 ProjectDataSyncPipeline 统一编排
     */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "PAYROLL";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "工资全量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 20, this::syncSingleProject);
    }

    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(project.getSourceProjectNum(), project.getAccount(), project.getPassword());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        int thirdPartyTotal = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A: 全量探针 =====
        // Step 1: 查询第1页获取第三方工资总量
        int globalTotal = 0;
        try {
            JSONObject probeData = apiClient.getPayrollPage(projectNum, 1, 1, "", account, password);
            if (probeData != null) {
                globalTotal = probeData.getInt("total", 0);
                log.info("项目【{}】第三方工资全量总量：{}", projectNum, globalTotal);
            }
        } catch (Exception e) {
            log.error("项目【{}】全量探针获取总量失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】工资探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (globalTotal == 0) {
            log.info("项目【{}】第三方无工资数据，尝试无月份同步", projectNum);
            SyncResult emptyResult = syncPayrollByMonth(projectNum, "", account, password);
            // 无月份同步也可能被风控拦截
            if (emptyResult.isAntiCrawlerTriggered()) {
                return emptyResult;
            }
            return emptyResult;
        }

        // Step 2: 计算最后一页并查询，取最旧的 payMonth
        int lastPageNum = (int) Math.ceil((double) globalTotal / 100);
        String oldestPayMonth = null;
        try {
            JSONObject lastPageData = apiClient.getPayrollPage(projectNum, lastPageNum, 100, "", account, password);
            if (lastPageData != null) {
                JSONArray lastPageList = lastPageData.getJSONArray("list");
                if (lastPageList != null) {
                    for (int i = 0; i < lastPageList.size(); i++) {
                        String payMonth = lastPageList.getJSONObject(i).getStr("payMonth");
                        if (payMonth != null && !payMonth.isEmpty()) {
                            if (oldestPayMonth == null || payMonth.compareTo(oldestPayMonth) < 0) {
                                oldestPayMonth = payMonth;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("项目【{}】获取最后一页工资数据失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】工资最后一页探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        // ===== Phase B: 生成月份列表 =====
        List<String> salaryMonths;
        if (oldestPayMonth != null) {
            // 从最旧月份到当前月份生成完整月份列表
            salaryMonths = new ArrayList<>();
            try {
                YearMonth start = YearMonth.parse(oldestPayMonth);
                YearMonth end = YearMonth.now();
                YearMonth current = start;
                while (!current.isAfter(end)) {
                    salaryMonths.add(current.toString());
                    current = current.plusMonths(1);
                }
                log.info("项目【{}】从最后一页获取到最早工资月份【{}】，生成{}个月份区间",
                        projectNum, oldestPayMonth, salaryMonths.size());
            } catch (Exception e) {
                log.warn("项目【{}】解析最早月份失败，回退到分页扫描: {}", projectNum, e.getMessage());
                salaryMonths = fetchSalaryMonths(projectNum, account, password);
            }
        } else {
            // 回退：分页扫描获取所有月份
            log.warn("项目【{}】最后一页未获取到工资月份，回退到分页扫描", projectNum);
            salaryMonths = fetchSalaryMonths(projectNum, account, password);
        }

        if (salaryMonths.isEmpty()) {
            return syncPayrollByMonth(projectNum, "", account, password);
        }

        log.info("项目【{}】共{}个工资月份待处理: {}", projectNum, salaryMonths.size(), salaryMonths);

        // ===== Phase C: 逐月同步（跳过已完全同步的月份） =====
        for (String month : salaryMonths) {
            // 检查该月是否已完全同步
            try {
                DiSyncSnapshot monthSnapshot = syncSnapshotService.getMonthSnapshot(projectNum, "PAYROLL", month);
                if (monthSnapshot != null && monthSnapshot.getMonthSyncDate() != null
                        && monthSnapshot.getMonthThirdPartyTotal() != null && monthSnapshot.getMonthThirdPartyTotal() > 0) {
                    // 检查本地该月数据量是否与第三方一致
                    long localMonthCount = payrollService.lambdaQuery()
                            .eq(DiPayroll::getSourceProjectNum, projectNum)
                            .eq(DiPayroll::getSalaryMonth, month)
                            .eq(DiPayroll::getDeleted, 0)
                            .count();
                    if (localMonthCount >= monthSnapshot.getMonthThirdPartyTotal()) {
                        log.info("项目【{}】月份【{}】已完全同步（本地{}>=第三方{}），跳过",
                                projectNum, month, localMonthCount, monthSnapshot.getMonthThirdPartyTotal());
                        thirdPartyTotal += monthSnapshot.getMonthThirdPartyTotal();
                        continue;
                    }
                }
            } catch (Exception e) {
                log.warn("项目【{}】检查月份【{}】快照失败，继续同步: {}", projectNum, month, e.getMessage());
            }

            log.info("项目【{}】同步工资月份: {}", projectNum, month);
            SyncResult monthResult = syncPayrollByMonth(projectNum, month, account, password);
            totalCount += monthResult.getTotalCount();
            successCount += monthResult.getSuccessCount();
            failCount += monthResult.getFailCount();
            skipCount += monthResult.getSkipCount();
            thirdPartyTotal += monthResult.getThirdPartyTotal();

            // 检查是否触发风控，若是则终止后续月份
            if (monthResult.isAntiCrawlerTriggered()) {
                antiCrawlerTriggered = true;
                log.warn("项目【{}】月份【{}】触发风控，终止后续工资同步", projectNum, month);
                break;
            }

            // ===== Phase D: 保存月份同步进度 =====
            if (monthResult.getThirdPartyTotal() > 0) {
                try {
                    syncSnapshotService.saveMonthSnapshot(
                            projectNum, "PAYROLL", month,
                            LocalDateTime.now(), monthResult.getThirdPartyTotal());
                    log.info("项目【{}】月份【{}】同步进度已保存：thirdTotal={}",
                            projectNum, month, monthResult.getThirdPartyTotal());
                } catch (Exception e) {
                    log.error("项目【{}】月份【{}】快照保存失败: {}", projectNum, month, e.getMessage());
                }
            }

            // 月份间延迟，避免API限流
            try {
                TimeUnit.MILLISECONDS.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("项目【{}】工资同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        // 记录工资同步快照：第三方总量 vs 本地总量（按来源统计）
        try {
            long localTotal = payrollService.lambdaQuery()
                    .eq(DiPayroll::getSourceProjectNum, projectNum)
                    .eq(DiPayroll::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(projectNum, "PAYROLL", thirdPartyTotal, (int) localTotal);
        } catch (Exception e) {
            log.error("项目【{}】工资同步快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    /**
     * 从首页数据中提取所有不重复的工资发放月份
     * 分页遍历第一页及后续页，收集所有 payMonth 值
     */
    private List<String> fetchSalaryMonths(String projectNum, String account, String password) {
        Set<String> months = new LinkedHashSet<>();
        int pageNum = 1;
        int pageSize = 100;

        try {
            while (true) {
                JSONObject data = apiClient.getPayrollPage(projectNum, pageNum, pageSize, "", account, password);
                if (data == null) {
                    break;
                }

                JSONArray list = data.getJSONArray("list");
                if (list == null || list.isEmpty()) {
                    break;
                }

                for (int i = 0; i < list.size(); i++) {
                    String payMonth = list.getJSONObject(i).getStr("payMonth");
                    if (payMonth != null && !payMonth.isEmpty()) {
                        months.add(payMonth);
                    }
                }

                // 判断是否还有下一页
                Integer total = data.getInt("total", 0);
                Integer respPageSize = data.getInt("pageSize", pageSize);
                if (pageNum * respPageSize >= total) {
                    break;
                }
                pageNum++;

                // 翻页延迟：1-3秒随机，避免API限流
                TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(1000, 3001));
            }
        } catch (Exception e) {
            log.error("项目【{}】获取工资月份列表失败: {}", projectNum, e.getMessage(), e);
        }

        return new ArrayList<>(months);
    }

    private SyncResult syncPayrollByMonth(String projectNum, String payMonth, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .build();
        ctx.putExtraParam("payMonth", payMonth);

        Set<String> processedSalaryIds = new HashSet<>();
        int month3rdPartyTotal = 0;

        try {
            List<JSONObject> payrollList = payrollCollector.collectAll(ctx);

            // 获取本月第三方数据总量
            if (ctx.getExtraParam("_thirdPartyTotal") != null) {
                month3rdPartyTotal = Integer.parseInt(ctx.getExtraParam("_thirdPartyTotal").toString());
            }
            if (payrollList == null || payrollList.isEmpty()) {
                return SyncResult.empty();
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("PAYROLL")
                    .build();

            // 批量清洗工资主表
            List<DiPayroll> payrollBatch = new ArrayList<>();
            for (JSONObject raw : payrollList) {
                totalCount++;
                String salaryId = raw.getStr("id");

                if (processedSalaryIds.contains(salaryId)) {
                    skipCount++;
                    continue;
                }

                try {
                    DiPayroll payroll = cleanPipeline.execute(raw, cleanCtx, "PAYROLL");
                    if (payroll != null) {
                        payrollBatch.add(payroll);
                        processedSalaryIds.add(salaryId);
                    } else {
                        skipCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("工资记录清洗失败: {}", salaryId, e);
                }
            }

            // 批量插入工资主表
            if (!payrollBatch.isEmpty()) {
                int batchSuccess = batchInsertService.batchInsertOrUpdate(payrollBatch, payrollService, 500);
                successCount += batchSuccess;
                failCount += (payrollBatch.size() - batchSuccess);

                // 批量发布变更事件 + 同步明细
                for (DiPayroll payroll : payrollBatch) {
                    if (batchSuccess > 0) {
                        dataChangePublisher.publish("PAYROLL", projectNum, payroll.getSalaryId(), "CREATE");
                        syncPayrollDetail(projectNum, payroll.getSalaryId(), account, password);
                    }
                }
            }

        } catch (Exception e) {
            log.error("项目【{}】工资月份【{}】同步失败: {}", projectNum, payMonth, e.getMessage());
            failCount++;
        }

        // 检测是否触发了风控
        if (Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"))) {
            log.warn("项目【{}】工资月份【{}】检测到风控触发", projectNum, payMonth);
            return SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount);
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount, month3rdPartyTotal);
    }

    private void syncPayrollDetail(String projectNum, String salaryId, String account, String password) {
        CollectContext detailCtx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .build();
        detailCtx.putExtraParam("salaryId", salaryId);

        try {
            List<JSONObject> detailList = payrollDetailCollector.collectAll(detailCtx);
            if (detailList == null || detailList.isEmpty()) {
                return;
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("PAYROLL_DETAIL")
                    .build();

            // 批量清洗明细
            List<DiPayrollDetail> detailBatch = new ArrayList<>();
            for (JSONObject raw : detailList) {
                try {
                    DiPayrollDetail detail = cleanPipeline.execute(raw, cleanCtx, "PAYROLL_DETAIL");
                    if (detail != null) {
                        detailBatch.add(detail);
                    }
                } catch (Exception e) {
                    log.error("工资明细清洗失败: salaryId={} detailId={}", salaryId, raw.getStr("id"), e);
                }
            }

            // 批量插入明细
            if (!detailBatch.isEmpty()) {
                batchInsertService.batchInsertOrUpdate(detailBatch, payrollDetailService, 500);
                for (DiPayrollDetail detail : detailBatch) {
                    dataChangePublisher.publish("PAYROLL_DETAIL", projectNum, detail.getDetailId(), "CREATE");
                }
            }

        } catch (Exception e) {
            log.error("工资明细同步失败: salaryId={}", salaryId, e);
        }
    }
}

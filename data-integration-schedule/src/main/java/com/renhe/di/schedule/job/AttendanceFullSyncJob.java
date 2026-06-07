package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.collector.AttendanceCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiAttendanceService;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 考勤全量同步任务（月份级串行采集）
 *
 * <p>工业级设计原则：
 * <ul>
 *   <li>逐月串行：单月份逐月顺序执行，避免并发请求触发第三方API风控</li>
 *   <li>月份内串行：单月份使用 collectAll()，避免账号级 Semaphore 冲突</li>
 *   <li>风控熔断：任一月份触发风控立即终止后续月份采集</li>
 *   <li>断点续传：月份级快照，已完成月份自动跳过</li>
 *   <li>结果聚合：所有月份结果汇总后返回</li>
 * </ul>
 *
 * <pre>
 * Phase A — 全局探针
 *   page=1, size=1 → globalTotal
 *
 * Phase B — 构建月份列表
 *   从最早考勤时间所在月 → 当前月
 *
 * Phase C — 逐月串行采集
 *   对每个月份顺序执行：
 *     1. 月份快照检查：本地 >= 第三方 → 跳过
 *     2. 月份探针：获取该月精确总量
 *     3. collectAll(ctx) 串行拉取整月数据
 *     4. 清洗 → 批量 upsert
 *     5. 保存月份总量快照
 *     6. 返回 MonthResult（含风控标记），风控则 break
 *
 * Phase D — 结果聚合与全局快照
 *   汇总所有月份结果 → 保存全局快照
 * </pre>
 */
@Slf4j
@Component
public class AttendanceFullSyncJob extends AbstractSyncJob {

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private DiAttendanceService attendanceService;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private ProjectSyncExecutor projectSyncExecutor;

    @Autowired
    private BatchInsertService batchInsertService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private RateLimitStrategy rateLimitStrategy;

    @Autowired
    private AttendanceCollector attendanceCollector;

    private static final int PAGE_SIZE = 100;

    // =========================================================
    // 外部调用入口（签名与原代码完全一致）
    // =========================================================

    /** 支持手动触发，由 ProjectDataSyncPipeline 统一编排，不单独参与定时调度 */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() { return "ATTENDANCE"; }

    @Override
    protected String getSyncType() { return "FULL"; }

    @Override
    protected String getTaskName() { return "考勤全量同步"; }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 200, this::syncSingleProject);
    }

    /** Pipeline 直接调用此 public 方法，签名不可变 */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(
                project.getSourceProjectNum(),
                project.getAccount(),
                project.getPassword(),
                project.getActualBeginDate()
        );
    }

    // =========================================================
    // 核心同步逻辑
    // =========================================================

    private SyncResult syncSingleProject(String projectNum, String account,
                                         String password, LocalDate actualBeginDate) {
        // ===== Phase A：全局探针 =====
        int thirdPartyTotal = probeGlobalTotal(projectNum, account, password);
        if (thirdPartyTotal < 0) {
            return SyncResult.antiCrawler(0, 0, 0, 0);
        }
        if (thirdPartyTotal == 0) {
            log.info("项目【{}】第三方无考勤数据，跳过", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // ===== Phase A2：获取最早 clockingTime 确定同步起点 =====
        LocalDateTime earliestClockingTime = probeEarliestClockingTime(
                projectNum, account, password, thirdPartyTotal);
        if (earliestClockingTime == null) {
            log.warn("项目【{}】无法获取最早考勤时间，使用 actualBeginDate 兜底", projectNum);
            earliestClockingTime = actualBeginDate != null
                    ? actualBeginDate.atStartOfDay()
                    : LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        }

        // ===== Phase B：构建月份列表（从最早考勤时间所在月开始） =====
        List<YearMonth> months = buildMonthList(earliestClockingTime);
        log.info("项目【{}】考勤同步范围：{} ~ {}，共 {} 个月，全局总量 {}，最早考勤时间：{}",
                projectNum, months.get(0), months.get(months.size() - 1),
                months.size(), thirdPartyTotal, earliestClockingTime);

        // ===== Phase C：逐月串行采集 =====
        List<MonthResult> results = new ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            YearMonth month = months.get(i);
            int idx = i + 1;
            log.info("项目【{}】月份【{}】[{}/{}] 开始采集",
                    projectNum, month, idx, months.size());
            MonthResult result = syncSingleMonth(projectNum, account, password, month, idx, months.size());
            results.add(result);
            if (result.antiCrawler) {
                log.warn("项目【{}】月份【{}】触发风控，终止后续月份采集", projectNum, month);
                break;
            }
        }
        // ===== Phase D：结果聚合 =====
        return aggregateResults(projectNum, results, thirdPartyTotal);
    }

    /**
     * 全局探针：获取无时间过滤的总数据量
     *
     * @return total >= 0，-1 表示触发风控
     */
    private int probeGlobalTotal(String projectNum, String account, String password) {
        try {
            JSONObject probe = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, 1, 1, "", "", account, password),
                    "ATTENDANCE", projectNum, 1, account);
            if (probe != null) {
                int total = probe.getInt("total", 0);
                log.info("项目【{}】考勤全局总量：{}", projectNum, total);
                return total;
            }
            return 0;
        } catch (Exception e) {
            log.error("项目【{}】考勤全局探针失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return -1;
            }
            return 0;
        }
    }

    /**
     * 获取最早 clockingTime（从最后一页取最早记录）
     *
     * @return 最早考勤时间，null 表示获取失败
     */
    private LocalDateTime probeEarliestClockingTime(String projectNum, String account,
                                                     String password, int total) {
        int lastPageNum = (int) Math.ceil((double) total / PAGE_SIZE);
        LocalDateTime earliest = null;
        try {
            JSONObject lastPage = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, lastPageNum, PAGE_SIZE,
                            "", "", account, password),
                    "ATTENDANCE", projectNum, lastPageNum, account);
            if (lastPage != null) {
                cn.hutool.json.JSONArray list = lastPage.getJSONArray("list");
                if (list != null) {
                    java.time.format.DateTimeFormatter dtFmt =
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    for (int i = 0; i < list.size(); i++) {
                        String ct = list.getJSONObject(i).getStr("clockingTime");
                        if (ct != null && !ct.isEmpty()) {
                            try {
                                LocalDateTime t = LocalDateTime.parse(ct, dtFmt);
                                if (earliest == null || t.isBefore(earliest)) {
                                    earliest = t;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            if (earliest != null) {
                log.info("项目【{}】最早考勤时间：{}（从最后一页推算）", projectNum, earliest);
            }
            return earliest;
        } catch (Exception e) {
            log.error("项目【{}】获取最早考勤时间失败: {}", projectNum, e.getMessage());
            return null;
        }
    }

    /**
     * 构建月份列表（从 earliestClockingTime 所在月到当前月）
     */
    private List<YearMonth> buildMonthList(LocalDateTime earliestClockingTime) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth start = earliestClockingTime != null
                ? YearMonth.from(earliestClockingTime)
                : YearMonth.of(2020, 1);
        YearMonth end = YearMonth.now();
        YearMonth cur = start;
        while (!cur.isAfter(end)) {
            months.add(cur);
            cur = cur.plusMonths(1);
        }
        return months;
    }

    /**
     * 单月份采集单元
     */
    private MonthResult syncSingleMonth(String projectNum, String account,
                                        String password, YearMonth month,
                                        int index, int totalMonths) {
        String monthId = month.toString();
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = month.atEndOfMonth().atTime(23, 59, 59);
        long monthStartMs = System.currentTimeMillis();

        // --- 1. 月份快照检查 ---
        Integer snapStatus = checkMonthSnapshot(projectNum, monthId, monthStart, monthEnd);
        if (snapStatus != null && snapStatus == -1) {
            log.info("项目【{}】月份【{}】[{}/{}] 快照匹配跳过，本地 >= 第三方",
                    projectNum, monthId, index, totalMonths);

            return MonthResult.skipped(monthId);
        }

        // --- 2. 月份探针 ---
        int monthThirdPartyTotal = probeMonthTotal(projectNum, account, password, monthStart, monthEnd);
        if (monthThirdPartyTotal < 0) {
            log.warn("项目【{}】月份【{}】[{}/{}] 探针触发风控",
                    projectNum, monthId, index, totalMonths);
            return MonthResult.antiCrawler(monthId);
        }
        if (monthThirdPartyTotal == 0) {
            log.info("项目【{}】月份【{}】[{}/{}] 无数据",
                    projectNum, monthId, index, totalMonths);
            return MonthResult.empty(monthId);
        }

        // --- 3. 通过 Collector 串行拉取整月数据 ---
        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .beginTime(monthStart)
                .endTime(monthEnd)
                .build();

        List<JSONObject> monthData;
        try {
            monthData = attendanceCollector.collectAll(ctx);

            if (Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"))) {
                log.warn("项目【{}】月份【{}】[{}/{}] 采集触发风控",
                        projectNum, monthId, index, totalMonths);
                return MonthResult.antiCrawler(monthId);
            }
        } catch (Exception e) {
            log.error("项目【{}】月份【{}】[{}/{}] 采集异常: {}",
                    projectNum, monthId, index, totalMonths, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】月份【{}】[{}/{}] 异常触发风控",
                        projectNum, monthId, index, totalMonths);
                return MonthResult.antiCrawler(monthId);
            }
            return MonthResult.failed(monthId, e.getMessage());
        }

        if (monthData == null || monthData.isEmpty()) {
            log.info("项目【{}】月份【{}】[{}/{}] 采集结果为空",
                    projectNum, monthId, index, totalMonths);
            return MonthResult.empty(monthId);
        }

        // --- 4. 清洗入库 ---
        MonthProcessResult processResult = processMonthData(
                projectNum, monthId, monthData, index, totalMonths);

        // --- 5. 保存月份总量快照 ---
        safeUpdateMonthSnapshot(projectNum, monthId, monthThirdPartyTotal);

        long durationSec = (System.currentTimeMillis() - monthStartMs) / 1000;
        log.info("项目【{}】月份【{}】[{}/{}] 完成，总计{}条/成功{}条/失败{}条/跳过{}条，耗时{}s",
                projectNum, monthId, index, totalMonths,
                processResult.totalCount, processResult.successCount,
                processResult.failCount, processResult.skipCount, durationSec);

        return MonthResult.success(monthId, processResult, monthThirdPartyTotal);
    }

    /**
     * 检查月份快照，返回已缓存的第三方总量（null=无快照，-1=已完成可跳过）
     */
    private Integer checkMonthSnapshot(String projectNum, String monthId,
                                       LocalDateTime monthStart, LocalDateTime monthEnd) {
        try {
            DiSyncSnapshot snap = syncSnapshotService.getMonthSnapshot(
                    projectNum, "ATTENDANCE", monthId);
            if (snap != null
                    && snap.getMonthThirdPartyTotal() != null
                    && snap.getMonthThirdPartyTotal() > 0) {

                long localCount = attendanceService.lambdaQuery()
                        .eq(DiAttendance::getSourceProjectNum, projectNum)
                        .eq(DiAttendance::getDeleted, 0)
                        .ge(DiAttendance::getAttendanceTime, monthStart)
                        .le(DiAttendance::getAttendanceTime, monthEnd)
                        .count();
                log.info("项目【{}】月份【{}】，本地({})，第三方({})",
                        projectNum, monthId, localCount, snap.getMonthThirdPartyTotal());

                if (localCount >= snap.getMonthThirdPartyTotal()) {
                    return -1; // 已完成标记
                }
                return snap.getMonthThirdPartyTotal();
            }
        } catch (Exception e) {
            log.warn("项目【{}】月份【{}】快照查询失败: {}", projectNum, monthId, e.getMessage());
        }
        return null;
    }

    /**
     * 月份探针：获取该月精确总量
     *
     * @return >=0 为总量，-1 表示风控
     */
    private int probeMonthTotal(String projectNum, String account, String password,
                                LocalDateTime monthStart, LocalDateTime monthEnd) {
        String beginStr = monthStart.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String endStr = monthEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            JSONObject probe = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, 1, 1, beginStr, endStr, account, password),
                    "ATTENDANCE", projectNum, 1, account);
            if (probe != null) {
                return probe.getInt("total", 0);
            }
            return 0;
        } catch (Exception e) {
            log.error("项目【{}】月份探针失败 [{} ~ {}]: {}", projectNum, beginStr, endStr, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return -1;
            }
            return 0;
        }
    }

    /**
     * 清洗并入库单月份数据
     */
    private MonthProcessResult processMonthData(String projectNum, String monthId,
                                                List<JSONObject> monthData,
                                                int index, int totalMonths) {
        CleanContext cleanCtx = CleanContext.builder()
                .sourceProjectNum(projectNum)
                .dataType("ATTENDANCE")
                .build();

        List<DiAttendance> batch = new ArrayList<>();
        int total = 0, success = 0, fail = 0, skip = 0;

        for (JSONObject raw : monthData) {
            total++;
            try {
                DiAttendance attendance = cleanPipeline.execute(raw, cleanCtx, "ATTENDANCE");
                if (attendance != null) {
                    batch.add(attendance);
                } else {
                    skip++;
                }
            } catch (Exception e) {
                fail++;
                log.error("项目【{}】月份【{}】清洗失败: id={}", projectNum, monthId, raw.getStr("id"), e);
            }
        }

        if (!batch.isEmpty()) {
            int batchSuccess = batchInsertService.batchInsertOrUpdate(batch, attendanceService, 500);
            success += batchSuccess;
            fail += (batch.size() - batchSuccess);

            for (DiAttendance att : batch) {
                dataChangePublisher.publish("ATTENDANCE", projectNum, att.getAttendanceId(), "CREATE");
            }
        }

        return new MonthProcessResult(total, success, fail, skip);
    }

    /**
     * 聚合所有月份结果（从 List<MonthResult>）
     */
    private SyncResult aggregateResults(String projectNum,
                                        List<MonthResult> results,
                                        int thirdPartyTotal) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        boolean antiCrawlerTriggered = false;
        int completedMonths = 0;
        int skippedMonths = 0;
        int failedMonths = 0;

        for (MonthResult result : results) {
            if (result == null) {
                failedMonths++;
                continue;
            }

            if (result.antiCrawler) {
                antiCrawlerTriggered = true;
                failedMonths++;
                log.warn("项目【{}】月份【{}】触发风控（账号级）", projectNum, result.monthId);
            } else if (result.skipped) {
                skippedMonths++;
            } else {
                totalCount += result.processResult.totalCount;
                successCount += result.processResult.successCount;
                failCount += result.processResult.failCount;
                skipCount += result.processResult.skipCount;
                completedMonths++;
            }
        }

        log.info("项目【{}】考勤月份串行采集汇总：完成{}个月/跳过{}个月/失败{}个月，"
                + "总计{}条/成功{}条/失败{}条/跳过{}条",
                projectNum, completedMonths, skippedMonths, failedMonths,
                totalCount, successCount, failCount, skipCount);

        // 保存全局一致性快照
        try {
            long localTotal = attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .eq(DiAttendance::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(
                    projectNum, "ATTENDANCE", thirdPartyTotal, (int) localTotal);
        } catch (Exception e) {
            log.error("项目【{}】考勤全局快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private void safeUpdateMonthSnapshot(String projectNum, String monthId, int total) {
        try {
            syncSnapshotService.saveMonthSnapshot(
                    projectNum, "ATTENDANCE", monthId, LocalDateTime.now(), total);
            log.debug("项目【{}】月份【{}】快照更新：total={}", projectNum, monthId, total);
        } catch (Exception e) {
            log.error("项目【{}】月份【{}】快照更新失败（主流程不受影响）: {}",
                    projectNum, monthId, e.getMessage());
        }
    }

    // =========================================================
    // 内部数据类
    // =========================================================

    /**
     * 单月份采集结果
     */
    private static class MonthResult {
        final String monthId;
        final boolean skipped;
        final boolean antiCrawler;
        final String errorMsg;
        final MonthProcessResult processResult;
        final int thirdPartyTotal;

        MonthResult(String monthId, boolean skipped, boolean antiCrawler,
                    String errorMsg, MonthProcessResult processResult, int thirdPartyTotal) {
            this.monthId = monthId;
            this.skipped = skipped;
            this.antiCrawler = antiCrawler;
            this.errorMsg = errorMsg;
            this.processResult = processResult;
            this.thirdPartyTotal = thirdPartyTotal;
        }

        static MonthResult skipped(String monthId) {
            return new MonthResult(monthId, true, false, null, null, 0);
        }

        static MonthResult empty(String monthId) {
            return new MonthResult(monthId, false, false, null,
                    new MonthProcessResult(0, 0, 0, 0), 0);
        }

        static MonthResult success(String monthId, MonthProcessResult result, int thirdPartyTotal) {
            return new MonthResult(monthId, false, false, null, result, thirdPartyTotal);
        }

        static MonthResult antiCrawler(String monthId) {
            return new MonthResult(monthId, false, true, null, null, 0);
        }

        static MonthResult failed(String monthId, String errorMsg) {
            return new MonthResult(monthId, false, false, errorMsg, null, 0);
        }
    }

    /**
     * 单月份数据处理结果
     */
    private static class MonthProcessResult {
        final int totalCount;
        final int successCount;
        final int failCount;
        final int skipCount;

        MonthProcessResult(int totalCount, int successCount, int failCount, int skipCount) {
            this.totalCount = totalCount;
            this.successCount = successCount;
            this.failCount = failCount;
            this.skipCount = skipCount;
        }
    }
}

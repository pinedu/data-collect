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
 * 考勤全量同步任务（月份级串行采集 + 逐页流式入库）
 *
 * <p>工业级设计原则：
 * <ul>
 *   <li>逐月串行：单月份逐月顺序执行，避免并发请求触发第三方API风控</li>
 *   <li>逐页流式：每采集一页立即清洗+upsert入库，避免整月数据积累在内存</li>
 *   <li>页码级断点续传：风控中断后下次从断点页码继续</li>
 *   <li>风控熔断：任一月份触发风控立即终止后续月份采集</li>
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
 * Phase C — 逐月串行采集（流式逐页）
 *   对每个月份顺序执行：
 *     1. 月份快照检查：已完成(-1)跳过，有断点则续传
 *     2. 月份探针：获取该月精确总量
 *     3. collectStreaming() 逐页拉取→清洗→upsert→检查点
 *     4. 整月完成后标记快照为已完成
 *     5. 风控则 break，下次从断点页续传
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
        List<SyncResult> monthResults = new ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            YearMonth month = months.get(i);
            int idx = i + 1;
            log.info("项目【{}】月份【{}】[{}/{}] 开始采集",
                    projectNum, month, idx, months.size());
            SyncResult result = syncSingleMonth(projectNum, account, password, month, idx, months.size());
            monthResults.add(result);
            if (result.isAntiCrawlerTriggered()) {
                log.warn("项目【{}】月份【{}】触发风控，终止后续月份采集", projectNum, month);
                break;
            }
        }
        // ===== Phase D：结果聚合 =====
        return aggregateResults(projectNum, monthResults, thirdPartyTotal);
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
     * 单月份采集单元（流式逐页入库 + 页码级断点续传）
     */
    private SyncResult syncSingleMonth(String projectNum, String account,
                                        String password, YearMonth month,
                                        int index, int totalMonths) {
        String monthId = month.toString();
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = month.atEndOfMonth().atTime(23, 59, 59);
        long monthStartMs = System.currentTimeMillis();

        // --- 1. 月份快照检查（支持页码级续传） ---
        MonthCheckResult check = checkMonthSnapshot(projectNum, monthId, monthStart, monthEnd);
        int startPage = check.resumeFromPage;
        // 标记：已完成月份需要探针验证数据是否变化
        boolean wasCompleted = check.wasCompleted;

        // --- 2. 月份探针（获取该月精确总量） ---
        int monthThirdPartyTotal = probeMonthTotal(projectNum, account, password, monthStart, monthEnd);
        if (monthThirdPartyTotal < 0) {
            log.warn("项目【{}】月份【{}】[{}/{}] 探针触发风控",
                    projectNum, monthId, index, totalMonths);
            return SyncResult.antiCrawler(0, 0, 0, 0);
        }
        if (monthThirdPartyTotal == 0) {
            log.info("项目【{}】月份【{}】[{}/{}] 无数据",
                    projectNum, monthId, index, totalMonths);
            safeMarkMonthComplete(projectNum, monthId, 0);
            return SyncResult.of(0, 0, 0, 0);
        }

        // 已完成月份：探针总量未变 → 跳过（数据无变化，无需重采）
        if (wasCompleted && monthThirdPartyTotal == check.snapshotThirdTotal) {
            log.info("项目【{}】月份【{}】[{}/{}] 已完成且总量未变({})，跳过",
                    projectNum, monthId, index, totalMonths, monthThirdPartyTotal);
            return SyncResult.of(0, 0, 0, 0);
        }

        // 已完成月份但数据量变化：从上次最后一页续传，获取增量数据
        if (wasCompleted && monthThirdPartyTotal != check.snapshotThirdTotal) {
            startPage = Math.max(check.snapshotLastPage, 1);
            log.info("项目【{}】月份【{}】[{}/{}] 已完成但总量变化({}→{})，从第{}页续传增量",
                    projectNum, monthId, index, totalMonths,
                    check.snapshotThirdTotal, monthThirdPartyTotal, startPage);
        }

        int monthTotalPages = (int) Math.ceil((double) monthThirdPartyTotal / PAGE_SIZE);
        if (startPage > 1) {
            log.info("项目【{}】月份【{}】[{}/{}] 断点续传，从第{}页继续（共{}页）",
                    projectNum, monthId, index, totalMonths, startPage, monthTotalPages);
        }

        // --- 3. 流式逐页采集 + 实时清洗入库 ---
        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .beginTime(monthStart)
                .endTime(monthEnd)
                .build();

        int[] pageStats = {0, 0, 0, 0}; // total, success, fail, skip
        int[] lastProcessedPage = {startPage - 1};
        boolean[] callbackFailed = {false};

        attendanceCollector.collectStreaming(ctx, startPage, (pageData, pageNum, total) -> {
            // 清洗本页数据
            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("ATTENDANCE")
                    .build();

            List<DiAttendance> batch = new ArrayList<>();
            for (JSONObject raw : pageData) {
                pageStats[0]++;
                try {
                    DiAttendance attendance = cleanPipeline.execute(raw, cleanCtx, "ATTENDANCE");
                    if (attendance != null) {
                        batch.add(attendance);
                    } else {
                        pageStats[3]++;
                    }
                } catch (Exception e) {
                    pageStats[2]++;
                    log.error("项目【{}】月份【{}】第{}页清洗失败: id={}",
                            projectNum, monthId, pageNum, raw.getStr("id"), e);
                }
            }

            // 批量 upsert 本页清洗结果
            if (!batch.isEmpty()) {
                try {
                    int batchSuccess = batchInsertService.batchInsertOrUpdate(
                            batch, attendanceService, 200);
                    pageStats[1] += batchSuccess;
                    pageStats[2] += (batch.size() - batchSuccess);

                    for (DiAttendance att : batch) {
                        dataChangePublisher.publish("ATTENDANCE", projectNum,
                                att.getAttendanceId(), "CREATE");
                    }
                } catch (Exception e) {
                    log.error("项目【{}】月份【{}】第{}页入库失败: {}",
                            projectNum, monthId, pageNum, e.getMessage());
                    pageStats[2] += batch.size();
                    callbackFailed[0] = true;
                    return false; // 入库失败，停止采集
                }
            }

            lastProcessedPage[0] = pageNum;

            // 保存页码级检查点
            safeSavePageCheckpoint(projectNum, monthId, pageNum, monthTotalPages, monthThirdPartyTotal);

            log.debug("项目【{}】月份【{}】第{}页入库完成，本页{}条/成功{}条",
                    projectNum, monthId, pageNum, pageData.size(), batch.size());
            return true; // 继续下一页
        });

        // --- 4. 采集结果判断 ---
        boolean antiCrawler = Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"));
        long durationSec = (System.currentTimeMillis() - monthStartMs) / 1000;

        log.info("项目【{}】月份【{}】[{}/{}] 完成，总计{}条/成功{}条/失败{}条/跳过{}条，耗时{}s",
                projectNum, monthId, index, totalMonths,
                pageStats[0], pageStats[1], pageStats[2], pageStats[3], durationSec);

        if (antiCrawler) {
            log.warn("项目【{}】月份【{}】[{}/{}] 采集触发风控，下次从第{}页续传",
                    projectNum, monthId, index, totalMonths, lastProcessedPage[0] + 1);
            return SyncResult.antiCrawler(pageStats[0], pageStats[1], pageStats[2], pageStats[3]);
        }

        if (callbackFailed[0]) {
            return SyncResult.of(pageStats[0], pageStats[1], pageStats[2], pageStats[3]);
        }

        // 整月采集完成，验证本地数据量与第三方一致
        try {
            long localCount = attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .eq(DiAttendance::getDeleted, 0)
                    .ge(DiAttendance::getAttendanceTime, monthStart)
                    .le(DiAttendance::getAttendanceTime, monthEnd)
                    .count();
            if (localCount < monthThirdPartyTotal) {
                log.warn("项目【{}】月份【{}】采集完成但本地({})<第三方({})，差{}条",
                        projectNum, monthId, localCount, monthThirdPartyTotal,
                        monthThirdPartyTotal - localCount);
            }
        } catch (Exception e) {
            log.warn("项目【{}】月份【{}】一致性校验查询失败: {}", projectNum, monthId, e.getMessage());
        }

        // 标记月份为已完成
        safeMarkMonthComplete(projectNum, monthId, monthThirdPartyTotal);

        return SyncResult.of(pageStats[0], pageStats[1], pageStats[2], pageStats[3]);
    }

    /**
     * 检查月份快照，判断是否完成或需要续传
     *
     * @return MonthCheckResult：complete=true 跳过；resumeFromPage>1 续传；=1 全新开始
     */
    private MonthCheckResult checkMonthSnapshot(String projectNum, String monthId,
                                                LocalDateTime monthStart, LocalDateTime monthEnd) {
        try {
            DiSyncSnapshot snap = syncSnapshotService.getMonthSnapshot(
                    projectNum, "ATTENDANCE", monthId);
            if (snap != null && snap.getMonthThirdPartyTotal() != null
                    && snap.getMonthThirdPartyTotal() > 0) {

                // lastCollectedPage = -1 表示整月已完成
                if (snap.getLastCollectedPage() != null && snap.getLastCollectedPage() == -1) {
                    long localCount = attendanceService.lambdaQuery()
                            .eq(DiAttendance::getSourceProjectNum, projectNum)
                            .eq(DiAttendance::getDeleted, 0)
                            .ge(DiAttendance::getAttendanceTime, monthStart)
                            .le(DiAttendance::getAttendanceTime, monthEnd)
                            .count();
                    int snapTotal = snap.getMonthThirdPartyTotal();
                    int snapLastPage = snap.getMonthTotalPages() != null ? snap.getMonthTotalPages() : 0;
                    log.info("项目【{}】月份【{}】已完成快照，本地({})，快照总量({})，快照末页({})",
                            projectNum, monthId, localCount, snapTotal, snapLastPage);
                    // 不在此处跳过，交给探针验证第三方数据是否变化
                    return MonthCheckResult.completed(snapTotal, snapLastPage);
                }

                // lastCollectedPage > 0 表示部分采集，可续传
                if (snap.getLastCollectedPage() != null && snap.getLastCollectedPage() > 0) {
                    int resumeFrom = snap.getLastCollectedPage() + 1;
                    log.info("项目【{}】月份【{}】发现断点，从第{}页续传（上次采集到第{}页，第三方总量{}）",
                            projectNum, monthId, resumeFrom, snap.getLastCollectedPage(),
                            snap.getMonthThirdPartyTotal());
                    return MonthCheckResult.resume(resumeFrom);
                }

                // 有快照但无页码记录（旧格式快照），从头开始
                log.info("项目【{}】月份【{}】存在旧格式快照，从头开始采集", projectNum, monthId);
            }
        } catch (Exception e) {
            log.warn("项目【{}】月份【{}】快照查询失败: {}", projectNum, monthId, e.getMessage());
        }
        return MonthCheckResult.freshStart();
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
     * 聚合所有月份结果
     */
    private SyncResult aggregateResults(String projectNum,
                                        List<SyncResult> monthResults,
                                        int thirdPartyTotal) {
        int totalCount = 0, successCount = 0, failCount = 0, skipCount = 0;
        boolean antiCrawlerTriggered = false;
        int completedMonths = 0, skippedMonths = 0;

        for (SyncResult r : monthResults) {
            totalCount += r.getTotalCount();
            successCount += r.getSuccessCount();
            failCount += r.getFailCount();
            skipCount += r.getSkipCount();
            if (r.isAntiCrawlerTriggered()) {
                antiCrawlerTriggered = true;
            }
            if (r.getTotalCount() == 0 && !r.isAntiCrawlerTriggered()) {
                skippedMonths++;
            } else {
                completedMonths++;
            }
        }

        log.info("项目【{}】考勤月份串行采集汇总：完成{}个月/跳过{}个月，"
                + "总计{}条/成功{}条/失败{}条/跳过{}条",
                projectNum, completedMonths, skippedMonths,
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

    private void safeSavePageCheckpoint(String projectNum, String monthId,
                                        int lastCollectedPage, int monthTotalPages,
                                        int monthThirdPartyTotal) {
        try {
            syncSnapshotService.savePageCheckpoint(
                    projectNum, "ATTENDANCE", monthId,
                    lastCollectedPage, monthTotalPages, monthThirdPartyTotal);
        } catch (Exception e) {
            log.warn("项目【{}】月份【{}】页码检查点保存失败（主流程不受影响）: {}",
                    projectNum, monthId, e.getMessage());
        }
    }

    private void safeMarkMonthComplete(String projectNum, String monthId, int monthThirdPartyTotal) {
        try {
            syncSnapshotService.markMonthComplete(projectNum, "ATTENDANCE", monthId, monthThirdPartyTotal);
        } catch (Exception e) {
            log.error("项目【{}】月份【{}】完成标记保存失败（主流程不受影响）: {}",
                    projectNum, monthId, e.getMessage());
        }
    }

    // =========================================================
    // 内部数据类
    // =========================================================

    /**
     * 月份快照检查结果
     */
    private static class MonthCheckResult {
        final boolean wasCompleted;    // 上次是否已完成
        final int resumeFromPage;      // 续传起始页
        final int snapshotThirdTotal;  // 快照中的第三方总量（用于对比探针）
        final int snapshotLastPage;    // 快照中的最后采集页码

        MonthCheckResult(boolean wasCompleted, int resumeFromPage,
                         int snapshotThirdTotal, int snapshotLastPage) {
            this.wasCompleted = wasCompleted;
            this.resumeFromPage = resumeFromPage;
            this.snapshotThirdTotal = snapshotThirdTotal;
            this.snapshotLastPage = snapshotLastPage;
        }

        static MonthCheckResult completed(int snapTotal, int snapLastPage) {
            return new MonthCheckResult(true, 1, snapTotal, snapLastPage);
        }

        static MonthCheckResult freshStart() {
            return new MonthCheckResult(false, 1, 0, 0);
        }

        static MonthCheckResult resume(int page) {
            return new MonthCheckResult(false, page, 0, 0);
        }
    }

}

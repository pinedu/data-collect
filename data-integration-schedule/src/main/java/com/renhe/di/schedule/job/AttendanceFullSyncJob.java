package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 考勤全量同步任务（逆序串行分页 + 页级断点续传）
 *
 * <p>采集策略（API 按 clockingTime DESC 排序，begin/end 精确到秒，过滤包含边界）：
 *
 * <pre>
 * Phase A — 探针
 *   1. page=1, size=1 → total
 *   2. 最后一页 → 最早 clockingTime → fullStartDate（全量采集起点）
 *
 * Phase B — 按月划分时间窗口
 *   fullStartDate 所在月 → 当前月，逐月处理
 *
 * Phase C — 逐月逆序串行分页
 *   对每个月：
 *     1. 读月份快照
 *        - 本地数量 >= 第三方月总量 → 跳过
 *        - 有 monthSyncDate → resumeEndTime = monthSyncDate（断点续传）
 *     2. 月份探针：begin=月初 end=resumeEndTime → monthTotal
 *     3. 从 lastPage → page=1 逐页串行：
 *        a. 调 API 拿数据
 *        b. 清洗 → 批量 upsert（ON DUPLICATE KEY UPDATE，天然幂等）
 *        c. 记录本页最早 clockingTime
 *        d. 立即更新月份快照（下次以此为 endTime 续传）
 *     4. 风控中断 → break，快照已保留当前进度
 *
 * Phase D — 全局一致性快照
 *   saveSnapshot(sourceProjectNum, ATTENDANCE, thirdTotal, localTotal)
 *
 * 断点续传语义：
 *   monthSyncDate = 已采集到的最早 clockingTime
 *   续传时 endTime = monthSyncDate（API 过滤包含边界，重复数据幂等更新）
 *   从新的 lastPage 开始重新逆序，只查 [月初, monthSyncDate] 范围内未采完的部分
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

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
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
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A：探针 =====
        // A1：page=1 size=1 → total
        int thirdPartyTotal = 0;
        try {
            JSONObject probe = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, 1, 1, "", "", account, password),
                    "ATTENDANCE", projectNum, 1, account);
            if (probe != null) {
                thirdPartyTotal = probe.getInt("total", 0);
            }
            log.info("项目【{}】考勤全量总量：{}", projectNum, thirdPartyTotal);
        } catch (Exception e) {
            log.error("项目【{}】考勤探针失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
            return SyncResult.of(0, 0, 1, 0);
        }

        if (thirdPartyTotal == 0) {
            log.info("项目【{}】第三方无考勤数据，跳过", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // A2：最后一页 → 最早 clockingTime → fullStartDate
        int lastPageNum = (int) Math.ceil((double) thirdPartyTotal / PAGE_SIZE);
        LocalDateTime fullStartDateTime = null;
        try {
            JSONObject lastPage = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, lastPageNum, PAGE_SIZE, "", "", account, password),
                    "ATTENDANCE", projectNum, lastPageNum, account);
            if (lastPage != null) {
                JSONArray list = lastPage.getJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String ct = list.getJSONObject(i).getStr("clockingTime");
                        if (ct != null && !ct.isEmpty()) {
                            try {
                                LocalDateTime t = LocalDateTime.parse(ct, DT_FMT);
                                if (fullStartDateTime == null || t.isBefore(fullStartDateTime)) {
                                    fullStartDateTime = t;
                                }
                            } catch (Exception ignored) {
                                log.warn("项目【{}】clockingTime 解析失败: {}", projectNum, ct);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("项目【{}】考勤最后一页探针失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        // 兜底：用 actualBeginDate 或默认值
        if (fullStartDateTime == null) {
            if (actualBeginDate != null) {
                fullStartDateTime = actualBeginDate.atStartOfDay();
                log.warn("项目【{}】最后一页无有效 clockingTime，使用实际开工日期: {}", projectNum, fullStartDateTime);
            } else {
                fullStartDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
                log.warn("项目【{}】最后一页无有效 clockingTime，使用默认起点: {}", projectNum, fullStartDateTime);
            }
        } else {
            log.info("项目【{}】全量同步起点（最早 clockingTime）：{}", projectNum, fullStartDateTime);
        }

        // ===== Phase B：按月划分时间窗口 =====
        YearMonth startMonth = YearMonth.from(fullStartDateTime);
        YearMonth endMonth = YearMonth.now();

        // ===== Phase C：逐月逆序串行分页 =====
        YearMonth currentMonth = startMonth;
        while (!currentMonth.isAfter(endMonth)) {

            String monthId = currentMonth.toString();
            LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
            LocalDateTime monthEnd = currentMonth.atEndOfMonth().atTime(23, 59, 59);

            log.info("项目【{}】开始同步考勤月份【{}】", projectNum, monthId);

            // --- 读快照，决定跳过 or 续传 ---
            LocalDateTime resumeEndTime = monthEnd; // 默认从月末往前查
            boolean needCollect = true;

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

                    if (localCount >= snap.getMonthThirdPartyTotal()) {
                        log.info("项目【{}】月份【{}】已完全同步（本地 {} >= 第三方 {}），跳过",
                                projectNum, monthId, localCount, snap.getMonthThirdPartyTotal());
                        needCollect = false;
                    } else if (snap.getMonthSyncDate() != null) {
                        // 断点续传：已同步到的最早时间作为新 endTime
                        // API 过滤包含边界，重复数据幂等写入
                        resumeEndTime = snap.getMonthSyncDate();
                        log.info("项目【{}】月份【{}】断点续传，endTime 缩短为: {}",
                                projectNum, monthId, resumeEndTime);
                    }
                }
            } catch (Exception e) {
                log.warn("项目【{}】查询月份【{}】快照失败，从月末全量: {}",
                        projectNum, monthId, e.getMessage());
            }

            if (!needCollect) {
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            // resumeEndTime 理论上不会早于月初，但做防御判断
            if (!resumeEndTime.isAfter(monthStart)) {
                log.info("项目【{}】月份【{}】续传终点已不晚于月初，视为完成", projectNum, monthId);
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            String beginStr = monthStart.format(DT_FMT);
            String endStr = resumeEndTime.format(DT_FMT);

            // 月份间限流延迟
            rateLimitStrategy.applyDelay("ATTENDANCE", projectNum, 1, account);

            // --- 月份探针：得到本次查询范围内的真实总量 ---
            int monthTotal = 0;
            try {
                JSONObject monthProbe = rateLimitStrategy.executeWithRetry(
                        () -> apiClient.getAttendancePage(projectNum, 1, 1, beginStr, endStr, account, password),
                        "ATTENDANCE", projectNum, 1, account);
                if (monthProbe != null) {
                    monthTotal = monthProbe.getInt("total", 0);
                }
                log.info("项目【{}】月份【{}】范围 [{}, {}] 数据量: {}",
                        projectNum, monthId, beginStr, endStr, monthTotal);
            } catch (Exception e) {
                log.error("项目【{}】月份【{}】探针失败: {}", projectNum, monthId, e.getMessage());
                if (isAntiCrawlerMessage(e)) {
                    antiCrawlerTriggered = true;
                    break;
                }
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            if (monthTotal == 0) {
                log.info("项目【{}】月份【{}】范围内无数据，跳过", projectNum, monthId);
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            // --- 逆序串行分页：lastPage → 1 ---
            int monthLastPage = (int) Math.ceil((double) monthTotal / PAGE_SIZE);

            // 获取账号级并发许可（Phase C 整段持有）
            if (!rateLimitStrategy.tryAcquire(account, "ATTENDANCE", 1)) {
                log.error("项目【{}】月份【{}】获取并发许可超时，跳过", projectNum, monthId);
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }
            try {

            // 追踪本月已采集到的最早 clockingTime（页级快照用）
            LocalDateTime monthMinClockingTime = null;
            boolean monthAntiCrawler = false;
            int monthSuccess = 0;
            int monthFail = 0;

            for (int page = monthLastPage; page >= 1; page--) {

                List<JSONObject> rawList = new ArrayList<>();
                try {
                    // 页间限流延迟（动态退避）
                    rateLimitStrategy.applyDelay("ATTENDANCE", projectNum, page, account);
                    final int currentPage = page;
                    JSONObject pageData = rateLimitStrategy.executeWithRetry(
                            () -> apiClient.getAttendancePage(projectNum, currentPage, PAGE_SIZE, beginStr, endStr, account, password),
                            "ATTENDANCE", projectNum, currentPage, account);
                    if (pageData == null) {
                        log.warn("项目【{}】月份【{}】第 {} 页返回 null，跳过", projectNum, monthId, page);
                        continue;
                    }
                    JSONArray listArr = pageData.getJSONArray("list");
                    if (listArr != null) {
                        for (int i = 0; i < listArr.size(); i++) {
                            rawList.add(listArr.getJSONObject(i));
                        }
                    }
                } catch (Exception e) {
                    log.error("项目【{}】月份【{}】第 {} 页采集失败: {}",
                            projectNum, monthId, page, e.getMessage());
                    if (isAntiCrawlerMessage(e)) {
                        monthAntiCrawler = true;
                        antiCrawlerTriggered = true;
                    }
                    break;
                }

                if (rawList.isEmpty()) {
                    continue;
                }

                // 清洗
                CleanContext cleanCtx = CleanContext.builder()
                        .sourceProjectNum(projectNum)
                        .dataType("ATTENDANCE")
                        .build();

                List<DiAttendance> batch = new ArrayList<>();
                for (JSONObject raw : rawList) {
                    totalCount++;

                    // 追踪最早 clockingTime
                    String ct = raw.getStr("clockingTime");
                    if (ct != null && !ct.isEmpty()) {
                        try {
                            LocalDateTime t = LocalDateTime.parse(ct, DT_FMT);
                            if (monthMinClockingTime == null || t.isBefore(monthMinClockingTime)) {
                                monthMinClockingTime = t;
                            }
                        } catch (Exception ignored) {
                            log.warn("项目【{}】clockingTime 解析失败: {}", projectNum, ct);
                        }
                    }

                    try {
                        DiAttendance attendance = cleanPipeline.execute(raw, cleanCtx, "ATTENDANCE");
                        if (attendance != null) {
                            batch.add(attendance);
                        } else {
                            skipCount++;
                        }
                    } catch (Exception e) {
                        failCount++;
                        monthFail++;
                        log.error("项目【{}】考勤清洗失败: id={}", projectNum, raw.getStr("id"), e);
                    }
                }

                // 批量 upsert（ON DUPLICATE KEY UPDATE，边界重复数据幂等处理）
                if (!batch.isEmpty()) {
                    int batchSuccess = batchInsertService.batchInsertOrUpdate(
                            batch, attendanceService, 500);
                    successCount += batchSuccess;
                    monthSuccess += batchSuccess;
                    int batchFail = batch.size() - batchSuccess;
                    failCount += batchFail;
                    monthFail += batchFail;

                    for (DiAttendance att : batch) {
                        dataChangePublisher.publish(
                                "ATTENDANCE", projectNum, att.getAttendanceId(), "CREATE");
                    }
                }

                log.info("项目【{}】月份【{}】第 {}/{} 页完成，本页 {} 条，月累计成功 {} 条",
                        projectNum, monthId, page, monthLastPage, rawList.size(), monthSuccess);

                // 每页立即持久化进度（风控中断后从此处续传）
                // monthMinClockingTime = 到目前为止已采集到的最早时间
                // 下次续传：endTime = monthMinClockingTime，覆盖该时间点及之前的数据
                if (monthMinClockingTime != null) {
                    safeUpdateMonthSnapshot(projectNum, monthId, monthMinClockingTime, monthTotal);
                }
            }

            log.info("项目【{}】月份【{}】{}，成功 {} 条，失败 {} 条",
                    projectNum, monthId,
                    monthAntiCrawler ? "风控中断" : "采集完成",
                    monthSuccess, monthFail);

            if (monthAntiCrawler) {
                break;
            }

            } finally {
                rateLimitStrategy.release(account, "ATTENDANCE", 1);
            }

            currentMonth = currentMonth.plusMonths(1);
        }

        log.info("项目【{}】考勤全量同步{}：总计 {} 条，成功 {} 条，失败 {} 条，跳过 {} 条",
                projectNum, antiCrawlerTriggered ? "风控中断" : "完成",
                totalCount, successCount, failCount, skipCount);

        // ===== Phase D：全局一致性快照 =====
        try {
            long localTotal = attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .eq(DiAttendance::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(
                    projectNum, "ATTENDANCE", thirdPartyTotal, (int) localTotal);
        } catch (Exception e) {
            log.error("项目【{}】考勤一致性快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    // =========================================================
    // 工具方法
    // =========================================================

    /**
     * 安全更新月份快照，异常不上抛。
     *
     * <p>快照语义：{@code monthSyncDate} = 本月已采集到的最早 clockingTime。
     * 下次续传：endTime = monthSyncDate，API 过滤包含边界，重复数据幂等写入。
     */
    private void safeUpdateMonthSnapshot(String projectNum, String monthId,
                                         LocalDateTime minClockingTime, int monthTotal) {
        try {
            syncSnapshotService.saveMonthSnapshot(
                    projectNum, "ATTENDANCE", monthId, minClockingTime, monthTotal);
            log.debug("项目【{}】月份【{}】快照更新：minClockingTime={}, total={}",
                    projectNum, monthId, minClockingTime, monthTotal);
        } catch (Exception e) {
            log.error("项目【{}】月份【{}】快照更新失败（主流程不受影响）: {}",
                    projectNum, monthId, e.getMessage());
        }
    }
}
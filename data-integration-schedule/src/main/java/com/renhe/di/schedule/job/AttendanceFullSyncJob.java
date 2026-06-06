package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.collector.AttendanceCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.core.exception.AntiCrawlerException;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 考勤全量同步任务
 * 每天21:00执行，按月分段全量同步
 */
@Slf4j
@Component
public class AttendanceFullSyncJob extends AbstractSyncJob {

    @Autowired
    private AttendanceCollector attendanceCollector;

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
    private ExecutorService syncTaskExecutor;

    @Autowired
    private ThirdPartyApiClient apiClient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 支持手动触发或独立调度考勤全量同步
     * 默认不参与定时调度，由 ProjectDataSyncPipeline 统一编排
     */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "ATTENDANCE";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "考勤全量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 200, this::syncSingleProject);
    }

    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(project.getSourceProjectNum(), project.getAccount(), project.getPassword(), project.getActualBeginDate());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password, LocalDate actualBeginDate) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A: 全量探针 =====
        // Step 1: 查询第1页获取第三方考勤总量
        int thirdPartyTotal = 0;
        try {
            JSONObject probeData = apiClient.getAttendancePage(projectNum, 1, 1, "", "", account, password);
            if (probeData != null) {
                thirdPartyTotal = probeData.getInt("total", 0);
                log.info("项目【{}】第三方考勤全量总量：{}", projectNum, thirdPartyTotal);
            }
        } catch (Exception e) {
            log.error("项目【{}】全量探针获取总量失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】考勤探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (thirdPartyTotal == 0) {
            log.info("项目【{}】第三方无考勤数据，跳过同步", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // Step 2: 计算最后一页并查询，取最旧的考勤时间作为 fullStartDate
        int lastPageNum = (int) Math.ceil((double) thirdPartyTotal / 100);
        LocalDateTime fullStartDate = null;
        try {
            JSONObject lastPageData = apiClient.getAttendancePage(projectNum, lastPageNum, 100, "", "", account, password);
            if (lastPageData != null) {
                JSONArray lastPageList = lastPageData.getJSONArray("list");
                if (lastPageList != null) {
                    for (int i = 0; i < lastPageList.size(); i++) {
                        JSONObject record = lastPageList.getJSONObject(i);
                        String clockingTime = record.getStr("clockingTime");
                        if (clockingTime != null && !clockingTime.isEmpty()) {
                            try {
                                LocalDateTime attTime = LocalDateTime.parse(clockingTime, FORMATTER);
                                if (fullStartDate == null || attTime.isBefore(fullStartDate)) {
                                    fullStartDate = attTime;
                                }
                            } catch (Exception e) {
                                log.warn("考勤时间解析失败: {}", clockingTime);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("项目【{}】获取最后一页数据失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】考勤最后一页探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (fullStartDate == null) {
            // 回退：使用 actualBeginDate 或默认值
            if (actualBeginDate != null) {
                fullStartDate = actualBeginDate.atStartOfDay();
                log.info("项目【{}】最后一页未获取到考勤时间，使用实际开工日期：{}", projectNum, fullStartDate);
            } else {
                fullStartDate = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
                log.warn("项目【{}】最后一页未获取到考勤时间，使用默认起始时间：{}", projectNum, fullStartDate);
            }
        } else {
            log.info("项目【{}】从最后一页获取到最早考勤时间作为同步起点：{}", projectNum, fullStartDate);
        }

        // ===== Phase B: 月份划分 + 范围确定 =====
        LocalDateTime currentMonth = fullStartDate.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endDate = LocalDateTime.now();
        int monthCount = 0;

        Set<String> processedIds = new HashSet<>();

        while (!currentMonth.isAfter(endDate)) {
            monthCount++;
            YearMonth ym = YearMonth.from(currentMonth);
            String monthId = ym.toString(); // yyyy-MM

            LocalDateTime monthStart = currentMonth;
            LocalDateTime monthEnd = ym.atEndOfMonth().atTime(23, 59, 59);
            if (monthEnd.isAfter(endDate)) {
                monthEnd = endDate;
            }

            // 检查是否有已保存的月份同步进度
            try {
                DiSyncSnapshot monthSnapshot =
                        syncSnapshotService.getMonthSnapshot(projectNum, "ATTENDANCE", monthId);
                if (monthSnapshot != null && monthSnapshot.getMonthSyncDate() != null
                        && monthSnapshot.getMonthThirdPartyTotal() != null && monthSnapshot.getMonthThirdPartyTotal() > 0) {
                    // 先检查数量一致性：本地该来源数据量 >= 上次记录的第三方总量 → 该月已完全同步
                    long localMonthCount = attendanceService.lambdaQuery()
                            .eq(DiAttendance::getSourceProjectNum, projectNum)
                            .eq(DiAttendance::getDeleted, 0)
                            .ge(DiAttendance::getAttendanceTime, currentMonth)
                            .le(DiAttendance::getAttendanceTime, ym.atEndOfMonth().atTime(23, 59, 59))
                            .count();
                    if (localMonthCount >= monthSnapshot.getMonthThirdPartyTotal()) {
                        log.info("项目【{}】月份【{}】已完全同步（本地{}>=第三方{}），跳过",
                                projectNum, monthId, localMonthCount, monthSnapshot.getMonthThirdPartyTotal());
                        currentMonth = currentMonth.plusMonths(1);
                        continue;
                    }
                    // 数量不一致但进度存在，缩短查询范围
                    LocalDateTime savedSyncDate = monthSnapshot.getMonthSyncDate();
                    if (savedSyncDate.isAfter(monthStart) && savedSyncDate.isBefore(monthEnd)) {
                        monthStart = savedSyncDate;
                        log.info("项目【{}】月份【{}】使用已保存的同步进度，缩短查询起点：{}",
                                projectNum, monthId, savedSyncDate);
                    }
                }
            } catch (Exception e) {
                log.warn("项目【{}】查询月份【{}】快照失败，使用月初作为起点: {}",
                        projectNum, monthId, e.getMessage());
            }

            // 如果查询起点已经超过月末，跳过该月
            if (!monthStart.isBefore(monthEnd)) {
                log.info("项目【{}】月份【{}】已完全同步，跳过", projectNum, monthId);
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            String beginTimeStr = monthStart.format(FORMATTER);
            String endTimeStr = monthEnd.format(FORMATTER);

            log.info("项目【{}】同步第{}个月【{}】：{} 至 {}", projectNum, monthCount, monthId, beginTimeStr, endTimeStr);

            // ===== Phase C: 按月份时间范围采集（使用现有并发分页） =====
            CollectContext ctx = CollectContext.builder()
                    .sourceProjectNum(projectNum)
                    .account(account)
                    .password(password)
                    .beginTime(monthStart)
                    .endTime(monthEnd)
                    .build();

            try {
                List<JSONObject> rawList = attendanceCollector.collectAllConcurrent(ctx, syncTaskExecutor);

                // 采集后立即检查风控标记（并发采集时异常被内部捕获，不会抛出）
                if (Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"))) {
                    log.error("项目【{}】第{}个月【{}】并发采集触发风控，终止当前项目考勤采集",
                            projectNum, monthCount, monthId);
                    antiCrawlerTriggered = true;
                    break;
                }

                LocalDateTime minAttendanceTime = null;
                // 以实际采集条数作为第三方总量（API的total在时间过滤下不可靠）
                int monthThirdPartyTotal = (rawList != null) ? rawList.size() : 0;

                if (rawList == null || rawList.isEmpty()) {
                    log.info("项目【{}】第{}个月【{}】无数据", projectNum, monthCount, monthId);
                } else {
                    CleanContext cleanCtx = CleanContext.builder()
                            .sourceProjectNum(projectNum)
                            .dataType("ATTENDANCE")
                            .build();

                    // 批量清洗
                    List<DiAttendance> attendanceList = new ArrayList<>();
                    for (JSONObject raw : rawList) {
                        totalCount++;
                        String dataId = raw.getStr("id");

                        // 去重检查
                        if (processedIds.contains(dataId)) {
                            skipCount++;
                            continue;
                        }

                        // 追踪最小考勤时间作为 monthSyncDate
                        String clockingTime = raw.getStr("clockingTime");
                        if (clockingTime != null && !clockingTime.isEmpty()) {
                            try {
                                LocalDateTime attTime = LocalDateTime.parse(clockingTime, FORMATTER);
                                if (minAttendanceTime == null || attTime.isBefore(minAttendanceTime)) {
                                    minAttendanceTime = attTime;
                                }
                            } catch (Exception e) {
                                log.warn("考勤时间解析失败: {}", clockingTime);
                            }
                        }

                        try {
                            DiAttendance attendance = cleanPipeline.execute(raw, cleanCtx, "ATTENDANCE");
                            if (attendance != null) {
                                attendanceList.add(attendance);
                                processedIds.add(dataId);
                            } else {
                                skipCount++;
                            }
                        } catch (Exception e) {
                            failCount++;
                            log.error("考勤记录清洗失败: {}", dataId, e);
                        }
                    }

                    // 批量插入或更新
                    if (!attendanceList.isEmpty()) {
                        int batchSuccess = batchInsertService.batchInsertOrUpdate(attendanceList, attendanceService, 500);
                        successCount += batchSuccess;
                        failCount += (attendanceList.size() - batchSuccess);

                        // 批量发布变更事件
                        for (DiAttendance att : attendanceList) {
                            if (batchSuccess > 0) {
                                dataChangePublisher.publish("ATTENDANCE", projectNum, att.getAttendanceId(), "CREATE");
                            }
                        }
                    }
                }

                // ===== Phase D: 保存月份同步进度 =====
                if (minAttendanceTime != null && monthThirdPartyTotal > 0) {
                    try {
                        syncSnapshotService.saveMonthSnapshot(
                                projectNum, "ATTENDANCE", monthId,
                                minAttendanceTime, monthThirdPartyTotal);
                        log.info("项目【{}】月份【{}】同步进度已保存：monthSyncDate={}, thirdTotal={}",
                                projectNum, monthId, minAttendanceTime, monthThirdPartyTotal);
                    } catch (Exception e) {
                        log.error("项目【{}】月份【{}】快照保存失败: {}",
                                projectNum, monthId, e.getMessage());
                    }
                }

                // 月份间延迟
                if (!currentMonth.plusMonths(1).isAfter(endDate)) {
                    TimeUnit.SECONDS.sleep(3);
                }

            } catch (AntiCrawlerException e) {
                // 触发风控 → 终止当前项目所有月份采集
                log.error("项目【{}】第{}个月【{}】触发风控，终止当前项目考勤采集: {}",
                        projectNum, monthCount, monthId, e.getMessage());
                failCount++;
                antiCrawlerTriggered = true;
                break;
            } catch (Exception e) {
                log.error("项目【{}】第{}个月【{}】同步失败: {}",
                        projectNum, monthCount, monthId, e.getMessage());
                failCount++;
                // 检查是否因风控导致的异常
                if (Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"))) {
                    antiCrawlerTriggered = true;
                    break;
                }
            }

            currentMonth = currentMonth.plusMonths(1);
        }

        log.info("项目【{}】考勤全量同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        // ===== Phase E: 一致性校验（按来源统计） =====
        try {
            long localTotal = attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .eq(DiAttendance::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(projectNum, "ATTENDANCE", thirdPartyTotal, (int) localTotal);
        } catch (Exception e) {
            log.error("项目【{}】考勤同步快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }
}

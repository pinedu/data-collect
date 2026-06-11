package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.collector.AttendanceCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiAttendanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 考勤增量同步任务
 * 每小时执行，同步最近变更数据
 */
@Slf4j
@Component
public class AttendanceIncrementalSyncJob extends AbstractSyncJob {

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

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每小时执行考勤增量同步（独立调度）
     * 考勤增量不依赖其他数据，可独立运行
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "ATTENDANCE";
    }

    @Override
    protected String getSyncType() {
        return "INCREMENTAL";
    }

    @Override
    protected String getTaskName() {
        return "考勤增量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 100, this::syncSingleProject);
    }

    private SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(project.getSourceProjectNum(), project.getAccount(), project.getPassword());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        // 获取最后同步时间
        LocalDateTime lastSyncTime = attendanceService.getLastAttendanceTime(projectNum);
        if (lastSyncTime == null) {
            // 如果没有记录，从24小时前开始
            lastSyncTime = LocalDateTime.now().minusHours(24);
        } else {
            // 往前推1小时，防止边界数据丢失
            lastSyncTime = lastSyncTime.minusHours(1);
        }

        LocalDateTime endTime = LocalDateTime.now();

        log.info("项目【{}】考勤增量同步：{} 至 {}",
                projectNum, lastSyncTime.format(FORMATTER), endTime.format(FORMATTER));

        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .beginTime(lastSyncTime)
                .endTime(endTime)
                .build();

        Set<String> processedIds = new HashSet<>();

        try {
            List<JSONObject> rawList = attendanceCollector.collectAll(ctx);
            if (rawList == null || rawList.isEmpty()) {
                log.info("项目【{}】增量无新数据", projectNum);
                return SyncResult.empty();
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("ATTENDANCE")
                    .build();

            // 批量清洗
            List<DiAttendance> attendanceList = new ArrayList<>();
            for (JSONObject raw : rawList) {
                totalCount++;
                String dataId = raw.getStr("id");

                if (processedIds.contains(dataId)) {
                    skipCount++;
                    continue;
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
                    log.error("考勤增量记录清洗失败: {}", dataId, e);
                }
            }

            // 批量插入或更新
            if (!attendanceList.isEmpty()) {
                int batchSuccess = batchInsertService.batchInsertOrUpdate(attendanceList, attendanceService, 200);
                successCount += batchSuccess;
                failCount += (attendanceList.size() - batchSuccess);

                // 批量发布变更事件
                for (DiAttendance att : attendanceList) {
                    if (batchSuccess > 0) {
                        dataChangePublisher.publish("ATTENDANCE", projectNum, att.getAttendanceId(), "CREATE");
                    }
                }
            }

        } catch (Exception e) {
            log.error("项目【{}】考勤增量同步失败: {}", projectNum, e.getMessage(), e);
            failCount++;
        }

        log.info("项目【{}】考勤增量同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }
}

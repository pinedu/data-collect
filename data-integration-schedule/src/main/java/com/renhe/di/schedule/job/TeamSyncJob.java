package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.collector.TeamCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.store.entity.DiTeam;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiTeamService;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 班组信息同步任务（全量）
 */
@Slf4j
@Component
public class TeamSyncJob extends AbstractSyncJob {

    @Autowired
    private TeamCollector teamCollector;

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private DiTeamService teamService;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private ProjectSyncExecutor projectSyncExecutor;

    @Autowired
    private BatchInsertService batchInsertService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    /**
     * 支持手动触发或独立调度班组信息同步
     * 默认不参与定时调度，由 ProjectDataSyncPipeline 统一编排
     */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "TEAM";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "班组信息全量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 50, this::syncSingleProject);
    }

    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(project.getSourceProjectNum(), project.getAccount(), project.getPassword());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .build();

        try {
            List<JSONObject> teamList = teamCollector.collectAll(ctx);
            if (teamList == null || teamList.isEmpty()) {
                return SyncResult.empty();
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(ctx.getSourceProjectNum())
                    .dataType("TEAM")
                    .build();

            // 批量清洗
            List<DiTeam> teamBatch = new ArrayList<>();
            for (JSONObject raw : teamList) {
                totalCount++;
                String dataId = raw.getStr("id");
                try {
                    DiTeam team = cleanPipeline.execute(raw, cleanCtx, "TEAM");
                    // 确保id已设置（第三方数据的主键）
                    if (team.getId() == null || team.getId().isEmpty()) {
                        team.setId(dataId);
                    }
                    teamBatch.add(team);
                } catch (Exception e) {
                    failCount++;
                    log.error("班组数据清洗失败: {}", dataId, e);
                }
            }

            // 批量插入或更新
            if (!teamBatch.isEmpty()) {
                int batchSuccess = batchInsertService.batchInsertOrUpdate(teamBatch, teamService, 200);
                successCount += batchSuccess;
                failCount += (teamBatch.size() - batchSuccess);
                // 批量发布变更事件
                for (DiTeam team : teamBatch) {
                    if (batchSuccess > 0) {
                        dataChangePublisher.publish("TEAM", projectNum, team.getTeamId(), "CREATE");
                    }
                }
            }

            // 记录同步快照：第三方总量 vs 本地总量
            try {
                int thirdPartyTotal = ctx.getExtraParam("_thirdPartyTotal") != null
                        ? Integer.parseInt(ctx.getExtraParam("_thirdPartyTotal").toString()) : totalCount;
                long localTotal = teamService.lambdaQuery()
                        .eq(DiTeam::getSourceProjectNum, projectNum)
                        .eq(DiTeam::getDeleted, 0)
                        .count();
                syncSnapshotService.saveSnapshot(projectNum, "TEAM", thirdPartyTotal, (int) localTotal);
            } catch (Exception e) {
                log.error("项目【{}】班组同步快照保存失败: {}", projectNum, e.getMessage());
            }

        } catch (Exception e) {
            log.error("项目【{}】班组同步失败: {}", projectNum, e.getMessage(), e);
            failCount++;
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }
}

package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.collector.ProjectCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目信息同步任务（全量）
 */
@Slf4j
@Component
public class ProjectSyncJob extends AbstractSyncJob {

    @Autowired
    private ProjectCollector projectCollector;

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private DiProjectService projectService;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private ProjectSyncExecutor projectSyncExecutor;

    /**
     * 支持手动触发或独立调度项目信息同步
     * 默认不参与定时调度，由 ProjectDataSyncPipeline 统一编排
     */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "PROJECT";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "项目信息全量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 30, this::syncSingleProject);
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
            List<JSONObject> projectList = projectCollector.collectAll(ctx);
            if (projectList == null || projectList.isEmpty()) {
                return SyncResult.empty();
            }

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("PROJECT")
                    .build();

            for (JSONObject raw : projectList) {
                totalCount++;
                String dataId = raw.getStr("id");

                try {
                    DiProject project = cleanPipeline.execute(raw, cleanCtx, "PROJECT");
                    if (project != null) {
                        // 确保id已设置（第三方数据的主键）
                        if (project.getId() == null || project.getId().isEmpty()) {
                            project.setId(dataId);
                        }
                        // 使用数据库唯一约束做UPSERT，避免并发或缓存导致的主键冲突
                        try {
                            projectService.save(project);
                            successCount++;
                            dataChangePublisher.publish("PROJECT", projectNum, dataId, "CREATE");
                        } catch (DuplicateKeyException dke) {
                            // 记录已存在，执行更新
                            projectService.updateById(project);
                            successCount++;
                            dataChangePublisher.publish("PROJECT", projectNum, dataId, "UPDATE");
                        }
                    } else {
                        skipCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("项目数据处理失败: {}", dataId, e);
                }
            }

        } catch (Exception e) {
            log.error("项目【{}】同步失败: {}", projectNum, e.getMessage(), e);
            failCount++;
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }
}

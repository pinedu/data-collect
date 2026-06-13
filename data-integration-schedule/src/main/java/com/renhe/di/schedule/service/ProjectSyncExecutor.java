package com.renhe.di.schedule.service;

import com.renhe.di.collect.api.AntiCrawlerDetector;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.schedule.job.SyncResult;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 多项目同步执行器
 * 支持虚拟线程 + Semaphore 并发控制
 */
@Slf4j
@Component
public class ProjectSyncExecutor {

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private ExecutorService syncTaskExecutor;

    /**
     * 执行多项目同步（仅同步有有效Token的项目）
     *
     * @param projectNum     指定项目编号（空则同步所有项目）
     * @param maxConcurrency 最大并发数
     * @param syncFunction   单项目同步函数
     * @return 汇总结果
     */
    public SyncResult execute(String projectNum, int maxConcurrency,
                               Function<DiProjectConfig, SyncResult> syncFunction) {
        List<DiProjectConfig> projects = getProjects(projectNum);
        if (projects.isEmpty()) {
            log.warn("未找到需要同步的项目");
            return SyncResult.empty();
        }

        // 过滤无Token的项目
        List<DiProjectConfig> validProjects = new ArrayList<>();
        for (DiProjectConfig project : projects) {
            if (tokenManager.hasToken(project.getAccount())) {
                validProjects.add(project);
            } else {
                log.warn("项目【{}】账号【{}】无有效Token，跳过同步", project.getProjectName(), project.getAccount());
            }
        }

        if (validProjects.isEmpty()) {
            log.warn("所有项目均无有效Token，同步结束");
            return SyncResult.empty();
        }

        projects = validProjects;

        log.info("开始多项目同步，项目数：{}，最大并发：{}", projects.size(), maxConcurrency);

        Semaphore semaphore = new Semaphore(maxConcurrency);
        List<CompletableFuture<ProjectSyncResult>> futures = new ArrayList<>();

        for (DiProjectConfig project : projects) {
            CompletableFuture<ProjectSyncResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    long start = System.currentTimeMillis();
                    log.info("项目【{}】开始同步", project.getSourceProjectNum());

                    SyncResult result = syncFunction.apply(project);

                    long duration = (System.currentTimeMillis() - start) / 1000;
                    log.info("项目【{}】同步完成，耗时：{}秒，结果：{}",
                            project.getSourceProjectNum(), duration, result);

                    return new ProjectSyncResult(project.getSourceProjectNum(), true, result, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ProjectSyncResult(project.getSourceProjectNum(), false, null, "等待并发许可被中断");
                } catch (Exception e) {
                    log.error("项目【{}】同步失败：{}", project.getSourceProjectNum(), e.getMessage(), e);
                    // Token已过期（401登录过期）→ 立即移除Token，避免后续项目无意义重试
                    if (AntiCrawlerDetector.isTokenExpired(e)) {
                        log.error("项目【{}】Token已过期（401），立即移除Token", project.getSourceProjectNum());
                        tokenManager.removeToken(project.getAccount());
                    }
                    return new ProjectSyncResult(project.getSourceProjectNum(), false, null, e.getMessage());
                } finally {
                    semaphore.release();
                }
            }, syncTaskExecutor);

            futures.add(future);
        }

        // 等待所有任务完成
        List<ProjectSyncResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 汇总结果
        return summarizeResults(results);
    }

    /**
     * 获取需要同步的项目列表
     */
    private List<DiProjectConfig> getProjects(String projectNum) {
        if (projectNum != null && !projectNum.isEmpty()) {
            DiProjectConfig config = projectConfigService.getBySourceProjectNum(projectNum);
            return config != null ? Collections.singletonList(config) : Collections.emptyList();
        }
        return projectConfigService.getAllActiveQxbProjects();
    }

    /**
     * 汇总所有项目同步结果
     */
    private SyncResult summarizeResults(List<ProjectSyncResult> results) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        int projectSuccess = 0;
        int projectFail = 0;

        for (ProjectSyncResult result : results) {
            if (result.success && result.syncResult != null) {
                totalCount += result.syncResult.getTotalCount();
                successCount += result.syncResult.getSuccessCount();
                failCount += result.syncResult.getFailCount();
                skipCount += result.syncResult.getSkipCount();
                projectSuccess++;
            } else {
                projectFail++;
                log.error("项目【{}】同步失败：{}", result.projectNum, result.errorMessage);
            }
        }

        log.info("多项目同步汇总：项目总数={}, 成功={}, 失败={}, 数据总计={}, 成功={}, 失败={}, 跳过={}",
                results.size(), projectSuccess, projectFail,
                totalCount, successCount, failCount, skipCount);

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    /**
     * 单项目同步结果
     */
    private record ProjectSyncResult(String projectNum, boolean success,
                                      SyncResult syncResult, String errorMessage) {
    }
}

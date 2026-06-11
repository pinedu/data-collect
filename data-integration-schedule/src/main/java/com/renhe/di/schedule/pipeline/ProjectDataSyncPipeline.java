package com.renhe.di.schedule.pipeline;

import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.schedule.job.*;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目数据同步流水线
 * 项目间并发执行，项目内按业务依赖顺序串行执行：项目 -> 班组 -> 人员 -> 工资 -> 考勤
 * 确保数据关联性正确
 */
@Slf4j
@Component
public class ProjectDataSyncPipeline {

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private ProjectSyncJob projectSyncJob;

    @Autowired
    private WarningIndicatorsSyncJob warningIndicatorsSyncJob;

    @Autowired
    private TeamSyncJob teamSyncJob;

    @Autowired
    private PersonSyncJob personSyncJob;

    @Autowired
    private PayrollSyncJob payrollSyncJob;

    @Autowired
    private AttendanceFullSyncJob attendanceFullSyncJob;

    @Autowired
    private SalaryAttendanceStatsSyncJob salaryAttendanceStatsSyncJob;

    @Autowired
    private SalaryAttendanceDetailSyncJob salaryAttendanceDetailSyncJob;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private ExecutorService syncTaskExecutor;

    @Autowired
    private RateLimitStrategy rateLimitStrategy;

    @Autowired
    private ThirdPartyApiClient apiClient;

    /**
     * 每天23:00执行全量流水线同步
     * 串行顺序：项目 -> 班组 -> 人员 -> 工资 -> 考勤
     * 仅同步有有效Token的项目
     */
    @Scheduled(cron = "0 0 23 * * ?")
    public void runFullSyncPipeline() {
        log.info("========== 项目数据全量同步流水线启动 ==========");

        List<DiProjectConfig> allProjects = projectConfigService.getAllActiveQxbProjects();
        if (allProjects.isEmpty()) {
            log.warn("未找到需要同步的项目配置");
            return;
        }

        // 过滤出有有效Token的项目
        List<DiProjectConfig> projects = new ArrayList<>();
        for (DiProjectConfig project : allProjects) {
            if (tokenManager.hasToken(project.getAccount())) {
                projects.add(project);
            } else {
                log.warn("项目【{}】账号【{}】无有效Token，跳过同步", project.getProjectName(), project.getAccount());
            }
        }

        if (projects.isEmpty()) {
            log.warn("所有项目均无有效Token，同步流水线结束");
            return;
        }

        log.info("待同步项目数：{}（已过滤无Token项目），开始并发执行：项目->班组->人员->工资->考勤", projects.size());

        AtomicInteger projectIndex = new AtomicInteger(0);
        int totalProjects = projects.size();

        // 每个项目提交为一个异步任务，项目间并发、项目内步骤串行
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DiProjectConfig project : projects) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                int idx = projectIndex.incrementAndGet();
                String projectNum = project.getSourceProjectNum();
                String projectName = project.getProjectName();

                log.info("\n--- 开始同步项目 [{}/{}]：{} ({}) ---", idx, totalProjects, projectName, projectNum);

                long projectStart = System.currentTimeMillis();

                // Step 1: 项目信息同步
                SyncResult projectResult = executeStep("项目信息", () -> projectSyncJob.syncSingleProject(project));
                if (projectResult == null) {
                    log.error("项目【{}】基础信息同步失败，跳过后续步骤", projectName);
                    return;
                }

                // Step 1-1: 项目6个百分百预警指标同步
                executeStep("预警指标", () -> warningIndicatorsSyncJob.syncSingleProject(project));

                // Step 2: 班组信息同步
                SyncResult teamResult = executeStep("班组信息", () -> teamSyncJob.syncSingleProject(project));
                if (teamResult == null) {
                    log.warn("项目【{}】班组同步失败，继续执行后续步骤", projectName);
                }

                // Step 3: 人员信息同步
                SyncResult personResult = executeStep("人员信息", () -> personSyncJob.syncSingleProject(project));
                if (personResult == null || (personResult.getSuccessCount() == 0 && personResult.getTotalCount() > 0)) {
                    log.warn("项目【{}】人员同步失败，继续执行后续步骤", projectName);
                }

                // 人员风控多轮恢复
                if (personResult != null && personResult.isAntiCrawlerTriggered()) {
                    boolean recovered = recoverFromAntiCrawler(project, projectName, projectNum, "PERSON");
                    if (!recovered) {
                        long projectDuration = (System.currentTimeMillis() - projectStart) / 1000;
                        log.info("--- 项目【{}】同步完成（风控中断），总耗时：{}s ---\n", projectName, projectDuration);
                        return;
                    }

                    // 风控已解除，重试人员同步（全量UPSERT幂等安全）
                    log.info("项目【{}】风控已解除，重试人员同步", projectName);
                    SyncResult retryPersonResult = executeStep("人员信息(续传)",
                            () -> personSyncJob.syncSingleProject(project));
                    if (retryPersonResult != null) {
                        int mergedTotal = personResult.getTotalCount() + retryPersonResult.getTotalCount();
                        int mergedSuccess = personResult.getSuccessCount() + retryPersonResult.getSuccessCount();
                        int mergedFail = personResult.getFailCount() + retryPersonResult.getFailCount();
                        log.info("项目【{}】人员续传完成，本轮合计：总计{}条，成功{}条，失败{}条",
                                projectName, mergedTotal, mergedSuccess, mergedFail);
                    }
                }
                

                // Step 3-1: 工资考勤统计同步
                executeStep("工资考勤统计", () -> salaryAttendanceStatsSyncJob.syncSingleProject(project));

                // Step 3-2: 工资考勤明细同步
                executeStep("工资考勤明细", () -> salaryAttendanceDetailSyncJob.syncSingleProject(project));

                // Step 4: 工资同步
                long payrollStartTime = System.currentTimeMillis();
                log.info("[时序] 项目【{}】工资启动 @ {}", projectName,
                        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                SyncResult payrollResult = executeStep("工资信息", () -> payrollSyncJob.syncSingleProject(project));
                long payrollDuration = (System.currentTimeMillis() - payrollStartTime) / 1000;
                log.info("[时序] 项目【{}】工资完成（耗时{}s）", projectName, payrollDuration);

                // 工资风控多轮恢复
                if (payrollResult != null && payrollResult.isAntiCrawlerTriggered()) {
                    boolean recovered = recoverFromAntiCrawler(project, projectName, projectNum, "PAYROLL");
                    if (!recovered) {
                        long projectDuration = (System.currentTimeMillis() - projectStart) / 1000;
                        log.info("--- 项目【{}】同步完成（工资风控中断），总耗时：{}s ---\n", projectName, projectDuration);
                        return;
                    }

                    log.info("项目【{}】风控已解除，重试工资同步（渐进式断点续传）", projectName);
                    SyncResult retryPayrollResult = executeStep("工资信息(续传)",
                            () -> payrollSyncJob.syncSingleProject(project));
                    if (retryPayrollResult != null) {
                        int mergedTotal = payrollResult.getTotalCount() + retryPayrollResult.getTotalCount();
                        int mergedSuccess = payrollResult.getSuccessCount() + retryPayrollResult.getSuccessCount();
                        int mergedFail = payrollResult.getFailCount() + retryPayrollResult.getFailCount();
                        log.info("项目【{}】工资续传完成，本轮合计：总计{}条，成功{}条，失败{}条",
                                projectName, mergedTotal, mergedSuccess, mergedFail);
                    }
                }

                // 间隔2秒再启动考勤
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Step 5: 考勤同步
                long attendanceStartTime = System.currentTimeMillis();
                log.info("[时序] 项目【{}】考勤启动 @ {}", projectName,
                        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                SyncResult attendanceResult = executeStep("考勤信息", () -> attendanceFullSyncJob.syncSingleProject(project));
                long attendanceDuration = (System.currentTimeMillis() - attendanceStartTime) / 1000;
                log.info("[时序] 项目【{}】考勤完成（耗时{}s）", projectName, attendanceDuration);

                // 考勤风控多轮恢复
                if (attendanceResult != null && attendanceResult.isAntiCrawlerTriggered()) {
                    boolean recovered = recoverFromAntiCrawler(project, projectName, projectNum, "ATTENDANCE");
                    if (!recovered) {
                        long projectDuration = (System.currentTimeMillis() - projectStart) / 1000;
                        log.info("--- 项目【{}】同步完成（考勤风控中断），总耗时：{}s ---\n", projectName, projectDuration);
                        return;
                    }

                    log.info("项目【{}】风控已解除，重试考勤同步（渐进式断点续传）", projectName);
                    SyncResult retryAttendanceResult = executeStep("考勤信息(续传)",
                            () -> attendanceFullSyncJob.syncSingleProject(project));
                    if (retryAttendanceResult != null) {
                        int mergedTotal = attendanceResult.getTotalCount() + retryAttendanceResult.getTotalCount();
                        int mergedSuccess = attendanceResult.getSuccessCount() + retryAttendanceResult.getSuccessCount();
                        int mergedFail = attendanceResult.getFailCount() + retryAttendanceResult.getFailCount();
                        log.info("项目【{}】考勤续传完成，本轮合计：总计{}条，成功{}条，失败{}条",
                                projectName, mergedTotal, mergedSuccess, mergedFail);
                    }
                }

                if (payrollResult == null) {
                    log.warn("项目【{}】工资同步失败", projectName);
                }
                if (attendanceResult == null) {
                    log.warn("项目【{}】考勤同步失败", projectName);
                }

                long projectDuration = (System.currentTimeMillis() - projectStart) / 1000;
                log.info("--- 项目【{}】同步完成，总耗时：{}秒 ---\n", projectName, projectDuration);
            }, syncTaskExecutor);
            futures.add(future);
        }

        // 等待所有项目同步完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("========== 项目数据全量同步流水线完成 ==========");
    }

    /**
     * 执行单个同步步骤
     * @param stepName 步骤名称
     * @param stepFunction 同步函数
     * @return SyncResult（成功或部分成功时返回，异常时返回null）
     */
    private SyncResult executeStep(String stepName, SyncStepFunction stepFunction) {
        long start = System.currentTimeMillis();
        try {
            log.info("[{}] 开始同步...", stepName);
            SyncResult result = stepFunction.execute();
            long duration = (System.currentTimeMillis() - start) / 1000;

            if (result.getFailCount() == 0 && !result.isAntiCrawlerTriggered()) {
                log.info("[{}] 同步完成，耗时{}秒，总计{}条，成功{}条",
                        stepName, duration, result.getTotalCount(), result.getSuccessCount());
            } else if (result.isAntiCrawlerTriggered()) {
                log.warn("[{}] 同步完成（触发风控），耗时{}秒，总计{}条，成功{}条，失败{}条",
                        stepName, duration, result.getTotalCount(), result.getSuccessCount(), result.getFailCount());
            } else {
                log.warn("[{}] 同步完成（部分失败），耗时{}秒，总计{}条，成功{}条，失败{}条，跳过{}条",
                        stepName, duration, result.getTotalCount(), result.getSuccessCount(),
                        result.getFailCount(), result.getSkipCount());
            }

            return result;
        } catch (Exception e) {
            long duration = (System.currentTimeMillis() - start) / 1000;
            log.error("[{}] 同步异常，耗时{}秒，错误：{}", stepName, duration, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 同步步骤函数式接口
     */
    @FunctionalInterface
    public interface SyncStepFunction {
        SyncResult execute() throws Exception;
    }

    /**
     * 风控多轮恢复：线性退避(5min→10min→15min→20min) + 同类型探针 + 最多1小时
     *
     * @param project     项目配置
     * @param projectName 项目名称（用于日志）
     * @param projectNum  项目编号
     * @param dataType    数据类型（PERSON/PAYROLL/ATTENDANCE），决定探针API类型
     * @return true=恢复成功，false=恢复失败
     */
    private boolean recoverFromAntiCrawler(DiProjectConfig project, String projectName,
                                            String projectNum, String dataType) {
        long antiCrawlerStart = System.currentTimeMillis();
        String account = project.getAccount();
        long[] cooldownSequence = {300_000, 600_000, 900_000, 1_200_000}; // 5min→10min→15min→20min
        long maxTotalRecoveryMs = 60 * 60 * 1000; // 最多等1小时
        boolean recovered = false;
        long totalWaited = 0;

        for (int round = 0; round < cooldownSequence.length; round++) {
            if (totalWaited >= maxTotalRecoveryMs) break;

            long cooldownMs = Math.min(cooldownSequence[round], maxTotalRecoveryMs - totalWaited);
            log.warn("项目【{}】[{}]风控恢复第{}/{}轮，等待{}s（已累计等待{}s/{}s上限）",
                    projectName, dataType, round + 1, cooldownSequence.length, cooldownMs / 1000,
                    totalWaited / 1000, maxTotalRecoveryMs / 1000);
            try {
                TimeUnit.MILLISECONDS.sleep(cooldownMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            totalWaited += cooldownMs;

            // 同类型探针：用对应数据类型的API探测
            try {
                sendProbe(projectNum, account, project.getPassword(), dataType);
                recovered = true;
                long totalSec = (System.currentTimeMillis() - antiCrawlerStart) / 1000;
                log.info("项目【{}】[{}]风控已解除！第{}轮探针成功，总恢复耗时{}s",
                        projectName, dataType, round + 1, totalSec);
                rateLimitStrategy.resetAntiCrawlerState(account);
                break;
            } catch (Exception probeEx) {
                if (rateLimitStrategy.isAntiCrawlerMessage(probeEx.getMessage())) {
                    log.error("项目【{}】[{}]第{}轮探针仍被拦截，继续下一轮",
                            projectName, dataType, round + 1);
                } else {
                    log.warn("项目【{}】[{}]第{}轮探针失败（非风控）: {}，尝试继续",
                            projectName, dataType, round + 1, probeEx.getMessage());
                    recovered = true;
                    break;
                }
            }
        }

        if (!recovered) {
            long totalSec = (System.currentTimeMillis() - antiCrawlerStart) / 1000;
            log.error("项目【{}】[{}]风控恢复失败，已等待{}s超过{}s上限",
                    projectName, dataType, totalSec, maxTotalRecoveryMs / 1000);
        }
        return recovered;
    }

    /**
     * 发送同类型探针请求，根据数据类型调用对应的API
     */
    private void sendProbe(String projectNum, String account, String password, String dataType) throws Exception {
        switch (dataType) {
            case "PERSON":
                apiClient.getPersonPage(projectNum, 1, 1, null, null, account, password);
                break;
            case "PAYROLL":
                apiClient.getPayrollPage(projectNum, 1, 1, "", account, password);
                break;
            case "ATTENDANCE":
                apiClient.getAttendancePage(projectNum, 1, 1, "", "", account, password);
                break;
            default:
                throw new IllegalArgumentException("未知数据类型: " + dataType);
        }
    }
}

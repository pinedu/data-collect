package com.renhe.di.bootstrap.controller;

import com.renhe.di.core.model.Result;
import com.renhe.di.schedule.job.*;
import com.renhe.di.schedule.pipeline.ProjectDataSyncPipeline;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 同步任务手动触发Controller
 * 提供HTTP接口手动触发各类同步任务
 */
@Slf4j
@RestController
@RequestMapping("/sync")
public class SyncTriggerController {

    @Autowired
    private ProjectDataSyncPipeline projectDataSyncPipeline;

    @Autowired
    private ProjectSyncJob projectSyncJob;

    @Autowired
    private TeamSyncJob teamSyncJob;

    @Autowired
    private PersonSyncJob personSyncJob;

    @Autowired
    private PayrollSyncJob payrollSyncJob;

    @Autowired
    private AttendanceFullSyncJob attendanceFullSyncJob;

    @Autowired
    private AttendanceIncrementalSyncJob attendanceIncrementalSyncJob;

    @Autowired
    private TokenRefreshJob tokenRefreshJob;

    @Autowired
    private DiProjectConfigService projectConfigService;

    // ==================== 流水线触发 ====================

    /**
     * 触发全量同步流水线（项目->班组->人员->工资->考勤）
     */
    @GetMapping("/pipeline/full")
    public Result<Void> triggerFullPipeline() {
        log.info("手动触发全量同步流水线");
        // 异步执行，避免HTTP超时
        new Thread(() -> projectDataSyncPipeline.runFullSyncPipeline()).start();
        return Result.success();
    }

    // ==================== 单类型全量同步 ====================

    /**
     * 触发项目信息同步
     */
    @PostMapping("/project")
    public Result<Void> triggerProjectSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("项目信息", () -> projectSyncJob.scheduledExecute());
    }

    /**
     * 触发班组信息同步
     */
    @PostMapping("/team")
    public Result<Void> triggerTeamSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("班组信息", () -> teamSyncJob.scheduledExecute());
    }

    /**
     * 触发人员信息同步
     */
    @PostMapping("/person")
    public Result<Void> triggerPersonSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("人员信息", () -> personSyncJob.scheduledExecute());
    }

    /**
     * 触发工资信息同步
     */
    @PostMapping("/payroll")
    public Result<Void> triggerPayrollSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("工资信息", () -> payrollSyncJob.scheduledExecute());
    }

    /**
     * 触发考勤全量同步
     */
    @PostMapping("/attendance/full")
    public Result<Void> triggerAttendanceFullSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("考勤全量", () -> attendanceFullSyncJob.scheduledExecute());
    }

    /**
     * 触发考勤增量同步
     */
    @PostMapping("/attendance/incremental")
    public Result<Void> triggerAttendanceIncrementalSync(
            @RequestParam(required = false) String projectNum) {
        return triggerSync("考勤增量", () -> attendanceIncrementalSyncJob.scheduledExecute());
    }

    /**
     * 触发Token续期
     */
    @PostMapping("/token/refresh")
    public Result<Void> triggerTokenRefresh() {
        log.info("手动触发Token续期");
        tokenRefreshJob.execute();
        return Result.success();
    }

    // ==================== 单项目全量同步 ====================

    /**
     * 对指定项目执行完整流水线同步
     */
    @PostMapping("/project/{sourceProjectNum}/full")
    public Result<Void> triggerProjectFullSync(@PathVariable String sourceProjectNum) {
        DiProjectConfig project = projectConfigService.getBySourceProjectNum(sourceProjectNum);
        if (project == null) {
            return Result.fail("项目不存在: " + sourceProjectNum);
        }

        log.info("手动触发项目【{}】全量同步流水线", sourceProjectNum);
        // 异步执行
        new Thread(() -> {
            try {
                projectSyncJob.syncSingleProject(project);
                teamSyncJob.syncSingleProject(project);
                personSyncJob.syncSingleProject(project);
                payrollSyncJob.syncSingleProject(project);
                attendanceFullSyncJob.syncSingleProject(project);
                log.info("项目【{}】全量同步流水线完成", sourceProjectNum);
            } catch (Exception e) {
                log.error("项目【{}】全量同步流水线失败", sourceProjectNum, e);
            }
        }).start();

        return Result.success();
    }

    // ==================== 私有方法 ====================

    private Result<Void> triggerSync(String syncName, SyncExecutor executor) {
        log.info("手动触发【{}】同步", syncName);
        // 异步执行避免HTTP超时
        new Thread(() -> {
            try {
                executor.execute();
                log.info("【{}】同步执行完成", syncName);
            } catch (Exception e) {
                log.error("【{}】同步执行失败", syncName, e);
            }
        }).start();
        return Result.success();
    }

    @FunctionalInterface
    interface SyncExecutor {
        void execute() throws Exception;
    }
}

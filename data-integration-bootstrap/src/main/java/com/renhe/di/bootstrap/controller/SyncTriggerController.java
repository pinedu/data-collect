package com.renhe.di.bootstrap.controller;

import com.renhe.di.attendancepoll.scheduler.AttendancePollScheduler;
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
    private AttendancePollScheduler attendancePollScheduler;

    @Autowired
    private TokenRefreshJob tokenRefreshJob;

    @Autowired
    private DiProjectConfigService projectConfigService;

    // ==================== 流水线触发 ====================

    /**
     * 流水线状态查询（项目独立循环模型已自动运行，无需手动触发）
     */
    @GetMapping("/pipeline/full")
    public Result<String> triggerFullPipeline() {
        log.info("查询流水线状态");
        return Result.success("流水线已自动运行，每个项目独立循环，无需手动触发");
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
     * 触发考勤同步（使用新的流式采集调度器）
     */
    @PostMapping("/attendance/full")
    public Result<Void> triggerAttendanceFullSync(
            @RequestParam(required = false) String projectNum) {
        if (projectNum == null || projectNum.isEmpty()) {
            return Result.fail("请指定项目编号 projectNum");
        }
        log.info("手动触发项目【{}】考勤同步", projectNum);
        new Thread(() -> {
            try {
                attendancePollScheduler.triggerProjectAndWait(projectNum);
                log.info("项目【{}】考勤同步完成", projectNum);
            } catch (Exception e) {
                log.error("项目【{}】考勤同步失败", projectNum, e);
            }
        }).start();
        return Result.success();
    }

    /**
     * 触发考勤增量同步（已废弃，统一使用全量同步）
     */
    @PostMapping("/attendance/incremental")
    public Result<String> triggerAttendanceIncrementalSync(
            @RequestParam(required = false) String projectNum) {
        log.warn("考勤增量同步接口已废弃，请使用 /attendance/full");
        return Result.success("请使用 /attendance/full 接口");
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
     * 对指定项目执行完整流水线同步（考勤使用新流式采集）
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
                attendancePollScheduler.triggerProjectAndWait(sourceProjectNum);
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

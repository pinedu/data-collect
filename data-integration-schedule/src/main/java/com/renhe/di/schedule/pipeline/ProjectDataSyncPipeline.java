package com.renhe.di.schedule.pipeline;

import com.renhe.di.attendancepoll.scheduler.AttendancePollScheduler;
import com.renhe.di.collect.api.AntiCrawlerDetector;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.api.TokenExpiredException;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.schedule.config.PipelineProperties;
import com.renhe.di.schedule.job.*;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目数据同步流水线 — 项目独立循环模型
 * <p>
 * 每个项目运行在独立的虚拟线程上，拥有自己的循环周期：
 * <pre>
 *   项目A: sync → sleep → sync → sleep → ...
 *   项目B: sync → sleep → sync → sleep → ...
 *   项目C: sync → sleep → sync → sleep → ...
 * </pre>
 * 项目间完全独立：项目A完成本轮后立即进入下一轮，不等项目B。
 * 项目内按业务依赖顺序串行：项目 → 预警 → 班组 → 人员 → 工资考勤 → 工资 → 考勤。
 */
@Slf4j
@Component
public class ProjectDataSyncPipeline implements ApplicationRunner {

    /** Redis分布式锁 Key */
    private static final String PIPELINE_LOCK_KEY = "pipeline:sync:lock";
    /** 锁过期时间（秒），看门狗会持续续期，此值仅作安全兜底 */
    private static final long LOCK_EXPIRE_SECONDS = 600;
    /** 看门狗续期间隔（毫秒），每 LOCK_EXPIRE/3 续一次锁 */
    private static final long WATCHDOG_INTERVAL_MS = (LOCK_EXPIRE_SECONDS / 3) * 1000;
    /** 获取锁失败时的重试间隔（毫秒） */
    private static final long LOCK_RETRY_INTERVAL_MS = 30_000;

    /** 全局停止标志，@PreDestroy 时置为 true */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 当前实例的锁标识，用于看门狗续期和释放时校验所有权 */
    private final AtomicReference<String> currentLockValue = new AtomicReference<>();

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
    private AttendancePollScheduler attendancePollScheduler;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PipelineProperties pipelineProperties;

    // =====================================================================
    // 启动入口：获取锁后为每个项目启动独立循环
    // =====================================================================

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("pipeline-bootstrap").start(() -> {
            log.info("流水线启动线程已启动，等待获取分布式锁...");
            while (!stopped.get()) {
                if (tryAcquireLock()) {
                    Thread watchdog = startWatchdog();
                    try {
                        startProjectLoops();
                    } catch (Exception e) {
                        log.error("流水线启动异常", e);
                    } finally {
                        stopWatchdog(watchdog);
                        releaseLock();
                    }
                    // startProjectLoops 返回后（stopped=true），不再重试
                    break;
                } else {
                    log.info("另一个实例正在执行流水线，等待{}s后重试", LOCK_RETRY_INTERVAL_MS / 1000);
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(LOCK_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        stopped.set(true);
        log.info("流水线收到关闭信号，停止所有项目循环");
    }

    // =====================================================================
    // 项目循环编排：加载项目列表，为每个项目启动独立虚拟线程
    // =====================================================================

    /**
     * 加载项目列表，为每个项目启动独立循环线程。
     * 本方法阻塞直到 stopped=true（应用关闭）或所有项目线程退出。
     */
    private void startProjectLoops() {
        if (currentLockValue.get() == null) {
            log.warn("startProjectLoops被调用时未持有分布式锁，拒绝执行");
            return;
        }
        log.info("========== 启动项目独立循环流水线 ==========");

        List<DiProjectConfig> allProjects = projectConfigService.getAllActiveQxbProjects();
        if (allProjects.isEmpty()) {
            log.warn("未找到需要同步的项目配置");
            return;
        }

        List<DiProjectConfig> projects = new ArrayList<>();
        for (DiProjectConfig project : allProjects) {
            if (tokenManager.hasToken(project.getAccount())) {
                projects.add(project);
            } else {
                log.warn("项目【{}】账号【{}】无有效Token，跳过同步", project.getProjectName(), project.getAccount());
            }
        }

        if (projects.isEmpty()) {
            log.warn("所有项目均无有效Token，流水线无法启动");
            return;
        }

        log.info("待同步项目数：{}（已过滤无Token项目），为每个项目启动独立循环", projects.size());

        // 每个项目启动一个独立虚拟线程，拥有自己的循环周期
        for (DiProjectConfig project : projects) {
            syncTaskExecutor.execute(() -> runProjectLoop(project));
        }

        // 阻塞等待直到 stopped=true（应用关闭）
        while (!stopped.get()) {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("========== 项目独立循环流水线已停止 ==========");
    }

    // =====================================================================
    // 单项目独立循环：sync → sleep → sync → sleep → ...
    // =====================================================================

    /**
     * 单个项目的独立循环。
     * 项目A完成本轮后等待配置的间隔时间，然后立即开始下一轮，不等其他项目。
     */
    private void runProjectLoop(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String projectName = project.getProjectName();
        log.info("项目【{}】独立循环已启动，轮次间隔: {}分钟", projectName, pipelineProperties.getRoundIntervalMinutes());

        while (!stopped.get()) {
            // 每轮开始前检查 Token 是否仍有效
            if (!tokenManager.hasToken(project.getAccount())) {
                log.warn("项目【{}】Token已失效，本轮跳过", projectName);
                sleepQuietly(pipelineProperties.getRoundIntervalMinutes() * 60L * 1000);
                continue;
            }

            try {
                syncOneProject(project);
            } catch (Exception e) {
                log.error("项目【{}】本轮同步异常: {}", projectName, e.getMessage(), e);
            }

            // 本轮完成，休眠配置的间隔后进入下一轮
            if (!stopped.get()) {
                sleepQuietly(pipelineProperties.getRoundIntervalMinutes() * 60L * 1000);
            }
        }

        log.info("项目【{}】独立循环已退出", projectName);
    }

    /**
     * 执行单个项目的完整同步流水线（项目内串行）
     */
    private void syncOneProject(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String projectName = project.getProjectName();
        long projectStart = System.currentTimeMillis();

        log.info("\n--- 开始同步项目：{} ({}) ---", projectName, projectNum);

        // Step 1: 项目信息同步
        SyncResult projectResult = executeStep("项目信息", project.getAccount(), () -> projectSyncJob.syncSingleProject(project));
        if (projectResult == null || projectResult.isTokenExpired()) {
            if (projectResult != null && projectResult.isTokenExpired()) {
                log.error("项目【{}】基础信息同步时Token已过期，终止该项目同步", projectName);
            } else {
                log.error("项目【{}】基础信息同步失败，跳过后续步骤", projectName);
            }
            return;
        }

        // Step 1-1: 项目6个百分百预警指标同步
        SyncResult warningResult = executeStep("预警指标", project.getAccount(), () -> warningIndicatorsSyncJob.syncSingleProject(project));
        if (warningResult != null && warningResult.isTokenExpired()) {
            log.error("项目【{}】预警指标同步时Token已过期，终止该项目同步", projectName);
            return;
        }

        // Step 2: 班组信息同步
        SyncResult teamResult = executeStep("班组信息", project.getAccount(), () -> teamSyncJob.syncSingleProject(project));
        if (teamResult != null && teamResult.isTokenExpired()) {
            log.error("项目【{}】班组同步时Token已过期，终止该项目同步", projectName);
            return;
        }
        if (teamResult == null) {
            log.warn("项目【{}】班组同步失败，继续执行后续步骤", projectName);
        }

        // Step 3: 人员信息同步
        SyncResult personResult = executeStep("人员信息", project.getAccount(), () -> personSyncJob.syncSingleProject(project));
        if (personResult != null && personResult.isTokenExpired()) {
            log.error("项目【{}】人员同步时Token已过期，终止该项目同步", projectName);
            return;
        }
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

            log.info("项目【{}】风控已解除，重试人员同步", projectName);
            SyncResult retryPersonResult = executeStep("人员信息(续传)", project.getAccount(),
                    () -> personSyncJob.syncSingleProject(project));
            if (retryPersonResult != null && retryPersonResult.isTokenExpired()) {
                log.error("项目【{}】人员续传时Token已过期，终止该项目同步", projectName);
                return;
            }
            if (retryPersonResult != null) {
                int mergedTotal = personResult.getTotalCount() + retryPersonResult.getTotalCount();
                int mergedSuccess = personResult.getSuccessCount() + retryPersonResult.getSuccessCount();
                int mergedFail = personResult.getFailCount() + retryPersonResult.getFailCount();
                log.info("项目【{}】人员续传完成，本轮合计：总计{}条，成功{}条，失败{}条",
                        projectName, mergedTotal, mergedSuccess, mergedFail);
            }
        }

        // Step 3-1: 工资考勤统计同步
        SyncResult statsResult = executeStep("工资考勤统计", project.getAccount(),
                () -> salaryAttendanceStatsSyncJob.syncSingleProject(project));
        if (statsResult != null && statsResult.isTokenExpired()) {
            log.error("项目【{}】工资考勤统计同步时Token已过期，终止该项目同步", projectName);
            return;
        }

        // 统计触发风控 → 明细直接跳过
        if (statsResult != null && statsResult.isAntiCrawlerTriggered()) {
            log.warn("项目【{}】工资考勤统计触发风控，跳过明细同步", projectName);
        } else if (statsResult != null && statsResult.getTotalCount() == 0 && statsResult.getFailCount() == 0) {
            log.info("项目【{}】工资考勤统计无变更，跳过明细同步", projectName);
        } else {
            SyncResult detailResult = executeStep("工资考勤明细", project.getAccount(),
                    () -> salaryAttendanceDetailSyncJob.syncSingleProject(project));
            if (detailResult != null && detailResult.isTokenExpired()) {
                log.error("项目【{}】工资考勤明细同步时Token已过期，终止该项目同步", projectName);
                return;
            }
        }

        // Step 4: 工资同步
        long payrollStartTime = System.currentTimeMillis();
        log.info("[时序] 项目【{}】工资启动 @ {}", projectName,
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        SyncResult payrollResult = executeStep("工资信息", project.getAccount(), () -> payrollSyncJob.syncSingleProject(project));
        if (payrollResult != null && payrollResult.isTokenExpired()) {
            log.error("项目【{}】工资同步时Token已过期，终止该项目同步", projectName);
            return;
        }
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
            SyncResult retryPayrollResult = executeStep("工资信息(续传)", project.getAccount(),
                    () -> payrollSyncJob.syncSingleProject(project));
            if (retryPayrollResult != null && retryPayrollResult.isTokenExpired()) {
                log.error("项目【{}】工资续传时Token已过期，终止该项目同步", projectName);
                return;
            }
            if (retryPayrollResult != null) {
                int mergedTotal = payrollResult.getTotalCount() + retryPayrollResult.getTotalCount();
                int mergedSuccess = payrollResult.getSuccessCount() + retryPayrollResult.getSuccessCount();
                int mergedFail = payrollResult.getFailCount() + retryPayrollResult.getFailCount();
                log.info("项目【{}】工资续传完成，本轮合计：总计{}条，成功{}条，失败{}条",
                        projectName, mergedTotal, mergedSuccess, mergedFail);
            }
        }

        // 间隔2秒再启动考勤
        sleepQuietly(2_000);

        // Step 5: 考勤采集
        long attendanceStartTime = System.currentTimeMillis();
        log.info("[时序] 项目【{}】考勤启动 @ {}", projectName,
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        attendancePollScheduler.triggerProjectAndWait(project.getSourceProjectNum());
        long attendanceDuration = (System.currentTimeMillis() - attendanceStartTime) / 1000;
        log.info("[时序] 项目【{}】考勤完成（耗时{}s）", projectName, attendanceDuration);

        if (payrollResult == null) {
            log.warn("项目【{}】工资同步失败", projectName);
        }

        long projectDuration = (System.currentTimeMillis() - projectStart) / 1000;
        log.info("--- 项目【{}】本轮同步完成，总耗时：{}秒 ---\n", projectName, projectDuration);
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // 分布式锁（保持不变）
    // =====================================================================

    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_LOCK_REDIS_SCRIPT;
    static {
        RELEASE_LOCK_REDIS_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
    }

    private boolean tryAcquireLock() {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(PIPELINE_LOCK_KEY, lockValue, Duration.ofSeconds(LOCK_EXPIRE_SECONDS));
        if (Boolean.TRUE.equals(acquired)) {
            currentLockValue.set(lockValue);
            log.info("流水线分布式锁获取成功，lockValue={}", lockValue);
            return true;
        }
        return false;
    }

    private void releaseLock() {
        String expected = currentLockValue.getAndSet(null);
        if (expected == null) return;
        try {
            Long deleted = stringRedisTemplate.execute(
                    RELEASE_LOCK_REDIS_SCRIPT,
                    List.of(PIPELINE_LOCK_KEY),
                    expected);
            if (Long.valueOf(1L).equals(deleted)) {
                log.info("流水线分布式锁已释放");
            } else {
                log.warn("锁已被其他实例持有或已过期，跳过释放");
            }
        } catch (Exception e) {
            log.warn("释放流水线分布式锁失败（锁会自动过期）: {}", e.getMessage());
        }
    }

    private Thread startWatchdog() {
        Thread watchdog = Thread.ofVirtual().name("pipeline-watchdog").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String expected = currentLockValue.get();
                if (expected == null) return;
                try {
                    String actual = stringRedisTemplate.opsForValue().get(PIPELINE_LOCK_KEY);
                    if (expected.equals(actual)) {
                        stringRedisTemplate.expire(PIPELINE_LOCK_KEY, Duration.ofSeconds(LOCK_EXPIRE_SECONDS));
                        log.debug("看门狗续锁成功，下次续期在{}s后", WATCHDOG_INTERVAL_MS / 1000);
                    } else {
                        log.warn("看门狗发现锁已被其他实例持有，停止续期");
                        return;
                    }
                } catch (Exception e) {
                    log.warn("看门狗续锁失败: {}", e.getMessage());
                }
            }
        });
        log.debug("看门狗线程已启动，每{}s续期一次", WATCHDOG_INTERVAL_MS / 1000);
        return watchdog;
    }

    private void stopWatchdog(Thread watchdog) {
        if (watchdog != null) {
            watchdog.interrupt();
            try {
                watchdog.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.debug("看门狗线程已停止");
        }
    }

    // =====================================================================
    // 同步步骤执行器（保持不变）
    // =====================================================================

    private SyncResult executeStep(String stepName, String account, SyncStepFunction stepFunction) {
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
        } catch (TokenExpiredException e) {
            long duration = (System.currentTimeMillis() - start) / 1000;
            log.error("[{}] Token已过期（401登录过期），立即移除Token并终止步骤，耗时{}秒", stepName, duration);
            tokenManager.removeToken(account);
            return SyncResult.tokenExpired();
        } catch (Exception e) {
            long duration = (System.currentTimeMillis() - start) / 1000;
            if (AntiCrawlerDetector.isTokenExpired(e)) {
                log.error("[{}] Token已过期（401，嵌套异常），立即移除Token并终止步骤，耗时{}秒", stepName, duration);
                tokenManager.removeToken(account);
                return SyncResult.tokenExpired();
            }
            log.error("[{}] 同步异常，耗时{}秒，错误：{}", stepName, duration, e.getMessage(), e);
            return null;
        }
    }

    @FunctionalInterface
    public interface SyncStepFunction {
        SyncResult execute() throws Exception;
    }

    // =====================================================================
    // 风控恢复（保持不变）
    // =====================================================================

    private boolean recoverFromAntiCrawler(DiProjectConfig project, String projectName,
                                            String projectNum, String dataType) {
        long antiCrawlerStart = System.currentTimeMillis();
        String account = project.getAccount();
        long[] cooldownSequence = {300_000, 600_000, 900_000, 1_200_000};
        long maxTotalRecoveryMs = 60 * 60 * 1000;
        boolean recovered = false;
        long totalWaited = 0;

        for (int round = 0; round < cooldownSequence.length; round++) {
            if (stopped.get()) return false;
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

            try {
                sendProbe(projectNum, account, project.getPassword(), dataType);
                recovered = true;
                long totalSec = (System.currentTimeMillis() - antiCrawlerStart) / 1000;
                log.info("项目【{}】[{}]风控已解除！第{}轮探针成功，总恢复耗时{}s",
                        projectName, dataType, round + 1, totalSec);
                rateLimitStrategy.resetAntiCrawlerState(account);
                break;
            } catch (Exception probeEx) {
                if (rateLimitStrategy.isAntiCrawlerException(probeEx)) {
                    log.error("项目【{}】[{}]第{}轮探针仍被风控拦截，继续下一轮",
                            projectName, dataType, round + 1);
                } else {
                    log.warn("项目【{}】[{}]第{}轮探针失败（非风控关键词匹配）: {}，继续下一轮等待",
                            projectName, dataType, round + 1, probeEx.getMessage());
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

package com.renhe.di.attendancepoll.scheduler;

import com.renhe.di.attendancepoll.config.AttendancePollProperties;
import com.renhe.di.attendancepoll.model.PollProgress;
import com.renhe.di.attendancepoll.model.PollStatus;
import com.renhe.di.attendancepoll.store.PollProgressStore;
import com.renhe.di.attendancepoll.task.AttendancePollTask;
import com.renhe.di.attendancepoll.watcher.TokenVersionWatcher;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiProjectConfigService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 考勤采集调度器
 * <p>
 * <b>核心原则：只响应 Pipeline 触发，不自动提交任何项目。</b>
 * <p>
 * Pipeline 通过 {@link #triggerProjectAndWait} 触发项目采集并阻塞等待完成，
 * 保证 Pipeline 在考勤采集完成前不会进入下一轮。
 */
@Slf4j
@Component
public class AttendancePollScheduler implements ApplicationRunner {

    /** 正在执行中的项目编号集合（防止重复提交） */
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    /** 调度器全局停止标志 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 已注册的项目配置缓存（projectNum → DiProjectConfig） */
    private final Map<String, DiProjectConfig> projectCache = new ConcurrentHashMap<>();

    private ThreadPoolExecutor pollExecutor;

    /** 停滞检测阈值：PROBING/COLLECTING/RECOVERING 状态超过此时间视为卡死（30分钟） */
    private static final long STALL_THRESHOLD_MS = 30 * 60 * 1000L;

    /** RECOVERING 状态下，若任务已入队但尚未开始执行，给予额外的排队容忍时间（20分钟） */
    private static final long RECOVERING_QUEUE_TOLERANCE_MS = 20 * 60 * 1000L;

    @Autowired
    private AttendancePollProperties properties;

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private AttendancePollTask pollTask;

    @Autowired
    private PollProgressStore progressStore;

    @Autowired
    private TokenVersionWatcher tokenVersionWatcher;

    // =====================================================================
    // ApplicationRunner：应用启动后自动启动调度循环
    // =====================================================================

    @Override
    public void run(ApplicationArguments args) {
        pollExecutor = new ThreadPoolExecutor(
                properties.getPoolCoreSize(),
                properties.getPoolMaxSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                Thread.ofVirtual().name("attendance-poll-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        Thread.ofVirtual().name("attendance-poll-scheduler").start(this::cacheRefreshLoop);
        log.info("考勤采集调度器已启动（coreSize={}, maxSize={}）",
                properties.getPoolCoreSize(), properties.getPoolMaxSize());
    }

    // =====================================================================
    // 对外 API（Pipeline 调用）
    // =====================================================================

    /**
     * 触发指定项目采集并阻塞等待完成（Pipeline Step 5 调用）
     * <p>
     * 不设超时 — 风控等冷却（~30分钟），Token等用户登录（~数天），均在同一次等待中处理。
     *
     * @param projectNum 项目编号
     */
    public void triggerProjectAndWait(String projectNum) {
        DiProjectConfig project = projectCache.get(projectNum);
        if (project == null) {
            log.warn("项目【{}】不在缓存中（缓存大小={}），无法触发考勤采集", projectNum, projectCache.size());
            return;
        }

        // 防止重复提交
        if (!runningProjects.add(projectNum)) {
            log.info("项目【{}】已在采集中，等待当前任务完成", projectNum);
            waitForCompletion(projectNum);
            return;
        }

        try {
            // 不重置进度 — Task 根据本地数据量自动判断从哪页开始
            // 提交采集任务
            submitAndTrack(project);

            // 等待完成（风控等冷却、Token等续期，不设超时）
            waitForCompletion(projectNum);
        } finally {
            runningProjects.remove(projectNum);
        }
    }

    public PollProgress getProjectStatus(String projectNum) {
        return progressStore.load(projectNum);
    }

    public List<PollProgress> getAllStatus() {
        List<PollProgress> result = new ArrayList<>();
        for (String projectNum : projectCache.keySet()) {
            result.add(progressStore.load(projectNum));
        }
        return result;
    }

    @PreDestroy
    public void shutdown() {
        stopped.set(true);
        tokenVersionWatcher.stopAll();
        if (pollExecutor != null) {
            pollExecutor.shutdown();
            try {
                if (!pollExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    pollExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                pollExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("考勤采集调度器已停止");
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    private void cacheRefreshLoop() {
        while (!stopped.get()) {
            try {
                refreshProjectCache();
                TimeUnit.SECONDS.sleep(properties.getPollIntervalSeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("缓存刷新循环异常", e);
                try {
                    TimeUnit.SECONDS.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("缓存刷新循环已退出");
    }

    private void refreshProjectCache() {
        try {
            List<DiProjectConfig> projects = projectConfigService.getAllActiveQxbProjects();
            for (DiProjectConfig project : projects) {
                String projectNum = project.getSourceProjectNum();
                projectCache.put(projectNum, project);
                tokenVersionWatcher.watch(projectNum, project.getAccount());
            }

            projectCache.keySet().removeIf(projectNum ->
                    projects.stream().noneMatch(p -> projectNum.equals(p.getSourceProjectNum())));

            log.info("项目配置缓存刷新完成，当前缓存项目数：{}", projectCache.size());
        } catch (Exception e) {
            log.warn("刷新项目配置缓存失败", e);
        }
    }

    private void submitAndTrack(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();

        // 队列深度监控
        int queueSize = pollExecutor.getQueue().size();
        int activeCount = pollExecutor.getActiveCount();
        if (queueSize > 0 || activeCount >= properties.getPoolMaxSize() - 2) {
            log.info("采集线程池压力：活跃{}/{}，队列排队{}/{}",
                    activeCount, properties.getPoolMaxSize(), queueSize, 256);
        }

        pollExecutor.submit(() -> {
            try {
                log.info("项目【{}】考勤采集任务开始执行", projectNum);
                boolean success = pollTask.collect(project);

                if (success) {
                    log.info("项目【{}】考勤采集任务执行完成", projectNum);
                } else {
                    PollStatus finalStatus = progressStore.getStatus(projectNum);
                    log.info("项目【{}】考勤采集任务中断，当前状态: {}", projectNum, finalStatus);
                }
            } catch (Exception e) {
                log.error("项目【{}】考勤采集任务异常: {}", projectNum, e.getMessage(), e);
                progressStore.saveStatus(projectNum, PollStatus.IDLE);
            }
        });
    }

    /**
     * 阻塞等待项目采集完成（风控等冷却、Token等续期，不设超时）
     * <p>
     * 退出条件：
     * <ul>
     *   <li>COMPLETED / IDLE → 正常结束</li>
     *   <li>stopped=true → 应用关闭</li>
     *   <li>PROBING/COLLECTING/RECOVERING 停滞超过阈值 → 任务可能卡死</li>
     * </ul>
     */
    private void waitForCompletion(String projectNum) {
        long pollIntervalMs = 3_000;

        // 停滞检测：记录上次状态和时间
        PollStatus lastStatus = null;
        long lastStatusChangeTime = System.currentTimeMillis();

        while (!stopped.get()) {
            PollStatus status = progressStore.getStatus(projectNum);

            // 状态变化时重置计时器
            if (status != lastStatus) {
                lastStatus = status;
                lastStatusChangeTime = System.currentTimeMillis();
            }

            switch (status) {
                case PROBING:
                case COLLECTING:
                case RECOVERING:
                    // 活跃状态停滞检测
                    long stuckMs = System.currentTimeMillis() - lastStatusChangeTime;

                    // RECOVERING 特殊处理：若任务已提交到线程池但尚未开始执行（状态未变为 PROBING），
                    // 给予额外排队容忍时间，避免极端场景下（40项目同时恢复）误触发停滞检测
                    long effectiveThreshold = (status == PollStatus.RECOVERING)
                            ? STALL_THRESHOLD_MS + RECOVERING_QUEUE_TOLERANCE_MS
                            : STALL_THRESHOLD_MS;

                    if (stuckMs > effectiveThreshold) {
                        log.warn("项目【{}】停滞在 {} 状态已超过 {}s（阈值{}s），可能任务卡死，强制结束等待",
                                projectNum, status, stuckMs / 1000, effectiveThreshold / 1000);
                        return;
                    }
                    break;

                case COMPLETED:
                case IDLE:
                    log.info("项目【{}】考勤采集已结束，状态: {}", projectNum, status);
                    return;

                case TOKEN_EXPIRED:
                    // Token 失效 → 等待外部用户重新登录，可能持续数天
                    // 每5分钟打一次日志，避免刷屏
                    if (System.currentTimeMillis() - lastStatusChangeTime < pollIntervalMs * 2) {
                        log.info("项目【{}】Token已失效，等待用户重新登录后自动恢复...", projectNum);
                    }
                    break;

                case RATE_LIMITED:
                    long untilMs = progressStore.getRateLimitUntil(projectNum);
                    long waitMs = untilMs - System.currentTimeMillis();
                    if (waitMs <= 0) {
                        log.info("项目【{}】风控冷却已到期，重新提交采集", projectNum);
                        // 设为 RECOVERING 而非 IDLE，防止 waitForCompletion 误返回
                        progressStore.saveStatus(projectNum, PollStatus.RECOVERING);

                        DiProjectConfig project = projectCache.get(projectNum);
                        if (project != null) {
                            submitAndTrack(project);
                        }
                        lastStatusChangeTime = System.currentTimeMillis();
                    } else {
                        log.debug("项目【{}】风控冷却中，剩余{}s", projectNum, waitMs / 1000);
                    }
                    break;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待项目【{}】考勤采集时被中断", projectNum);
                return;
            }
        }

        log.info("调度器已停止，退出等待项目【{}】", projectNum);
    }
}

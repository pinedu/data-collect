package com.renhe.di.attendancepoll.watcher;

import com.renhe.di.attendancepoll.config.AttendancePollProperties;
import com.renhe.di.attendancepoll.model.PollStatus;
import com.renhe.di.attendancepoll.store.PollProgressStore;
import com.renhe.di.collect.api.TokenManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 项目级 Token 版本监听器
 * <p>
 * 每个被监听的项目分配一个虚拟线程，轮询 Redis Key
 * {@code attendance:poll:token_version:{projectNum}} 的值变化。
 * <ul>
 *   <li>版本号变化（Token 已续期 / 重新扫码）→ 将项目状态从 TOKEN_EXPIRED 恢复为 IDLE</li>
 *   <li>Key 被删除（Token 已移除）→ 将项目状态设为 TOKEN_EXPIRED</li>
 * </ul>
 */
@Slf4j
@Component
public class TokenVersionWatcher {

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 10_000;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AttendancePollProperties properties;

    @Autowired
    private PollProgressStore progressStore;

    @Autowired
    private TokenManager tokenManager;

    /** 正在监听的项目 -> 监听线程映射 */
    private final Map<String, Thread> watchers = new ConcurrentHashMap<>();

    /** 各项目已知的最新版本号（内存缓存，避免每次读 Redis） */
    private final Map<String, String> knownVersions = new ConcurrentHashMap<>();

    /** 全局停止标志 */
    private volatile boolean stopped = false;

    /**
     * 开始监听指定项目的 Token 版本变化
     * <p>
     * 若该项目已有监听线程则跳过（幂等）。
     *
     * @param projectNum 项目编号
     * @param account    项目关联账号（用于检查 Token 是否存在）
     */
    public void watch(String projectNum, String account) {
        if (watchers.containsKey(projectNum)) {
            return;
        }

        // 初始化已知版本号（ConcurrentHashMap 不允许 null value，用空字符串代替）
        String key = properties.getTokenVersionKeyPrefix() + projectNum;
        String currentVersion = redisTemplate.opsForValue().get(key);
        knownVersions.put(projectNum, currentVersion != null ? currentVersion : "");

        Thread thread = Thread.ofVirtual()
                .name("token-watcher-" + projectNum)
                .start(() -> watchLoop(projectNum, account, key));

        watchers.put(projectNum, thread);
        log.info("TokenVersionWatcher 开始监听项目【{}】", projectNum);
    }

    /**
     * 停止监听指定项目
     */
    public void unwatch(String projectNum) {
        Thread thread = watchers.remove(projectNum);
        if (thread != null) {
            thread.interrupt();
            knownVersions.remove(projectNum);
            log.info("TokenVersionWatcher 停止监听项目【{}】", projectNum);
        }
    }

    /**
     * 停止所有监听
     */
    @PreDestroy
    public void stopAll() {
        stopped = true;
        Set<String> projectNums = Set.copyOf(watchers.keySet());
        for (String projectNum : projectNums) {
            unwatch(projectNum);
        }
        log.info("TokenVersionWatcher 全部监听已停止");
    }

    /**
     * 获取当前正在监听的项目数量
     */
    public int watchCount() {
        return watchers.size();
    }

    // =====================================================================
    // 内部监听循环
    // =====================================================================

    private void watchLoop(String projectNum, String account, String key) {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            try {
                String newVersion = redisTemplate.opsForValue().get(key);
                String oldVersion = knownVersions.get(projectNum);
                // 空字符串表示"无版本"（ConcurrentHashMap 不允许 null）
                String safeNew = newVersion != null ? newVersion : "";
                String safeOld = oldVersion != null ? oldVersion : "";

                // 情况1：版本号发生变化（Token 已续期或重新扫码）
                if (!safeNew.isEmpty() && !safeNew.equals(safeOld)) {
                    log.info("项目【{}】Token 版本变化：{} → {}，恢复采集状态",
                            projectNum, safeOld.isEmpty() ? "(无)" : safeOld, safeNew);
                    knownVersions.put(projectNum, safeNew);
                    progressStore.saveTokenVersion(projectNum, safeNew);

                    // 从 TOKEN_EXPIRED 恢复为 IDLE
                    PollStatus currentStatus = progressStore.getStatus(projectNum);
                    if (currentStatus == PollStatus.TOKEN_EXPIRED) {
                        progressStore.saveStatus(projectNum, PollStatus.IDLE);
                        log.info("项目【{}】从 TOKEN_EXPIRED 恢复为 IDLE", projectNum);
                    }
                    continue;
                }

                // 情况2：Key 被删除（Token 已移除）
                if (safeNew.isEmpty() && !safeOld.isEmpty()) {
                    log.warn("项目【{}】Token 版本 Key 已删除，标记为 TOKEN_EXPIRED", projectNum);
                    knownVersions.put(projectNum, "");
                    progressStore.saveStatus(projectNum, PollStatus.TOKEN_EXPIRED);
                    continue;
                }

                // 情况3：双方都无版本 + Token 不存在 → 标记 TOKEN_EXPIRED
                if (safeNew.isEmpty() && safeOld.isEmpty()) {
                    if (!tokenManager.hasToken(account)) {
                        PollStatus currentStatus = progressStore.getStatus(projectNum);
                        if (currentStatus != PollStatus.TOKEN_EXPIRED) {
                            log.warn("项目【{}】账号【{}】无有效 Token，标记为 TOKEN_EXPIRED",
                                    projectNum, account);
                            progressStore.saveStatus(projectNum, PollStatus.TOKEN_EXPIRED);
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("TokenVersionWatcher 项目【{}】轮询异常: {}", projectNum, e.getMessage());
            }
        }
        log.debug("TokenVersionWatcher 项目【{}】监听线程已退出", projectNum);
    }
}

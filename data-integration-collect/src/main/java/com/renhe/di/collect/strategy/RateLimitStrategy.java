package com.renhe.di.collect.strategy;

import com.renhe.di.core.exception.AntiCrawlerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 反爬虫/限流策略 — 工业级分级限流 + 线性退避风控冷却
 * <p>
 * 核心机制：
 * 1. 按数据类型分级限流：PERSON 低频/PAYROLL 中频/ATTENDANCE 高频
 * 2. 风控触发后线性退避冷却（5min→10min→15min→…→30min封顶），冷却后自动探测恢复
 * 3. 单账号并发控制（Semaphore）：同一账号按数据类型控制并发数
 * 4. 动态退避：成功时逐步缩减延迟，失败时指数增长延迟
 * 5. 风控跟踪：记录触发时间、冷却时长，供上层日志分析
 */
@Slf4j
@Component
public class RateLimitStrategy {

    // ==================== 基础限流参数 ====================
    /** 最小分页延迟（毫秒） */
    private static final int MIN_DELAY_MS = 1000;
    /** 最大分页延迟（毫秒） */
    private static final int MAX_DELAY_MS = 3000;
    /** 最大重试次数 */
    private static final int MAX_RETRY = 2;
    /** 动态退避的基数因子 */
    private static final double BACKOFF_BASE = 2.0;
    /** 动态退避衰减因子（成功时延迟缩小比例） */
    private static final double BACKOFF_DECAY = 0.8;
    /** 单账号最大延迟（毫秒） */
    private static final long MAX_BACKOFF_MS = 60_000;

    // ==================== 分级限流参数（按数据类型） ====================
    /** 并发采集：并发请求数 */
    private static final int CONCURRENCY_PERSON = 1;
    private static final int CONCURRENCY_PAYROLL = 5;
    private static final int CONCURRENCY_ATTENDANCE = 15;
    private static final int CONCURRENCY_DEFAULT = 15;

    /** 并发采集：批次大小（每批页数，与并发数对齐避免线程排队超时） */
    private static final int BATCH_PERSON = 1;
    private static final int BATCH_PAYROLL = 5;
    private static final int BATCH_ATTENDANCE = 15;
    private static final int BATCH_DEFAULT = 100;

    /** 批次间冷却：最小延迟（毫秒） */
    private static final int INTER_BATCH_MIN_PERSON = 8_000;
    private static final int INTER_BATCH_MIN_PAYROLL = 3_000;
    private static final int INTER_BATCH_MIN_ATTENDANCE = 2_000;
    private static final int INTER_BATCH_MIN_DEFAULT = 2_000;

    /** 批次间冷却：最大延迟（毫秒） */
    private static final int INTER_BATCH_MAX_PERSON = 12_000;
    private static final int INTER_BATCH_MAX_PAYROLL = 6_000;
    private static final int INTER_BATCH_MAX_ATTENDANCE = 4_000;
    private static final int INTER_BATCH_MAX_DEFAULT = 4_000;

    /** 并发请求前最小抖动（毫秒） */
    private static final int JITTER_MIN_MS = 2000;
    /** 并发请求前最大抖动（毫秒） */
    private static final int JITTER_MAX_MS = 5000;

    // ==================== 风控冷却退避参数 ====================
    /** 风控冷却基数（毫秒）— 每次触发递增5分钟 */
    private static final long ANTI_CRAWLER_BASE_COOLDOWN_MS = 300_000;
    /** 风控冷却最大上限（毫秒）= 30分钟 */
    private static final long ANTI_CRAWLER_MAX_COOLDOWN_MS = 1_800_000;

    // ==================== 状态存储 ====================
    /** 账号级并发信号量 */
    private final ConcurrentHashMap<String, Semaphore> accountSemaphores = new ConcurrentHashMap<>();

    /** 动态退避计数器：记录连续成功次数 */
    private final ConcurrentHashMap<String, AtomicInteger> successStreak = new ConcurrentHashMap<>();
    /** 动态退避计数器：记录连续失败次数 */
    private final ConcurrentHashMap<String, AtomicInteger> failStreak = new ConcurrentHashMap<>();

    /** 账号风控触发次数（用于计算指数退避等待时间） */
    private final ConcurrentHashMap<String, AtomicInteger> antiCrawlerTriggerCount = new ConcurrentHashMap<>();
    /** 账号风控触发时间戳（毫秒），key = account */
    private final ConcurrentHashMap<String, Long> antiCrawlerTriggerTime = new ConcurrentHashMap<>();

    // ---- 限流关键词（匹配即视为反爬虫/风控拦截） ----
    private static final String[] ANTI_CRAWLER_KEYWORDS = {
        "账号异常", "频繁", "限制", "系统异常", "请联系管理员"
    };

    /**
     * 获取或创建账号级信号量（按数据类型限制并发数）
     */
    private Semaphore getAccountSemaphore(String account, String dataType) {
        int maxConcurrency = getMaxConcurrency(dataType);
        String key = account + ":" + dataType;
        return accountSemaphores.computeIfAbsent(key, k -> new Semaphore(maxConcurrency));
    }

    /**
     * 应用分页间延迟（动态退避）
     *
     * @param dataType   数据类型
     * @param projectNum 项目编号
     * @param pageNum    页码
     * @param account    账号（用于动态退避）
     */
    public void applyDelay(String dataType, String projectNum, int pageNum, String account) {
        int baseDelay = MIN_DELAY_MS + ThreadLocalRandom.current().nextInt(MAX_DELAY_MS - MIN_DELAY_MS + 1);
        long adjustedDelay = applyDynamicBackoff(account, baseDelay);
        log.debug("[{}] 项目【{}】第{}页延迟{}ms（基础{}ms）", dataType, projectNum, pageNum, adjustedDelay, baseDelay);
        try {
            TimeUnit.MILLISECONDS.sleep(adjustedDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] 项目【{}】延迟被中断", dataType, projectNum);
        }
    }

    /**
     * 兼容旧调用（无账号参数），使用默认延迟
     */
    public void applyDelay(String dataType, String projectNum, int pageNum) {
        int delay = MIN_DELAY_MS + ThreadLocalRandom.current().nextInt(MAX_DELAY_MS - MIN_DELAY_MS + 1);
        log.debug("[{}] 项目【{}】第{}页延迟{}ms", dataType, projectNum, pageNum, delay);
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] 项目【{}】延迟被中断", dataType, projectNum);
        }
    }

    /**
     * 获取账号级信号量许可（带超时）
     *
     * @param account 账号
     * @return 是否获取成功
     */
    public boolean tryAcquire(String account) {
        return tryAcquire(account, "DEFAULT", 1);
    }

    /**
     * 获取账号级信号量许可（带超时，支持数据类型+许可数）
     */
    public boolean tryAcquire(String account, String dataType, int permits) {
        Semaphore semaphore = getAccountSemaphore(account, dataType);
        try {
            boolean acquired = semaphore.tryAcquire(permits, 120, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("账号[{}]数据类型[{}]并发信号量获取超时（120s），请求{}个许可", account, dataType, permits);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("账号[{}]并发信号量获取被中断", account);
            return false;
        }
    }

    /**
     * 释放账号级信号量
     */
    public void release(String account, String dataType, int permits) {
        String key = account + ":" + dataType;
        Semaphore semaphore = accountSemaphores.get(key);
        if (semaphore != null) {
            semaphore.release(permits);
        }
    }

    /** 兼容旧调用 */
    public void release(String account) {
        release(account, "DEFAULT", 1);
    }

    public void release(String account, int permits) {
        release(account, "DEFAULT", permits);
    }

    /**
     * 带重试和并发控制的执行
     * <p>
     * 注意：并发采集模式下，遇到风控/反爬虫响应直接抛出异常，由上层决定终止整个项目采集，
     * 避免继续请求加重风控。
     *
     * @param supplier   执行逻辑
     * @param dataType   数据类型
     * @param projectNum 项目编号
     * @param pageNum    页码
     * @param account    账号（用于风控追踪）
     * @param <T>        返回类型
     * @return 执行结果
     */
    public <T> T executeWithRetry(Supplier<T> supplier, String dataType, String projectNum,
                                   int pageNum, String account) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY) {
            try {
                T result = supplier.get();
                // 请求成功，记录成功
                recordSuccess(projectNum);
                return result;
            } catch (Exception e) {
                retryCount++;
                lastException = e;
                String msg = e.getMessage();
                boolean isAntiCrawler = isAntiCrawlerMessage(msg);

                // 记录失败（动态退避）
                recordFailure(projectNum);

                if (isAntiCrawler) {
                    // 记录风控触发（指数退避冷却追踪）
                    recordAntiCrawlerTrigger(account);
                    // 触发风控 → 直接抛异常，终止当前项目采集，不重试
                    log.error("[{}] 项目【{}】第{}页触发反爬虫/风控，终止当前项目采集：{}",
                            dataType, projectNum, pageNum, msg);
                    throw new AntiCrawlerException(
                            String.format("[%s] 项目【%s】触发风控：%s", dataType, projectNum, msg), e);
                } else {
                    long waitMs = (long) Math.pow(BACKOFF_BASE, retryCount) * 1000;
                    log.warn("[{}] 项目【{}】第{}页请求失败，等待{}ms后重试（{}/{}）：{}",
                            dataType, projectNum, pageNum, waitMs, retryCount, MAX_RETRY, msg);
                    sleep(waitMs);
                }
            }
        }

        throw new RuntimeException(String.format("[%s] 项目【%s】第%d页请求失败，已达最大重试次数%d",
                dataType, projectNum, pageNum, MAX_RETRY), lastException);
    }

    // ==================== 并发采集辅助方法（数据类型感知） ====================

    /**
     * 根据数据类型获取最大并发数
     */
    public int getMaxConcurrency(String dataType) {
        if ("PERSON".equalsIgnoreCase(dataType)) return CONCURRENCY_PERSON;
        if ("PAYROLL".equalsIgnoreCase(dataType)) return CONCURRENCY_PAYROLL;
        if ("ATTENDANCE".equalsIgnoreCase(dataType)) return CONCURRENCY_ATTENDANCE;
        return CONCURRENCY_DEFAULT;
    }

    /**
     * 根据数据类型获取批次大小
     */
    public int getBatchSize(String dataType) {
        if ("PERSON".equalsIgnoreCase(dataType)) return BATCH_PERSON;
        if ("PAYROLL".equalsIgnoreCase(dataType)) return BATCH_PAYROLL;
        if ("ATTENDANCE".equalsIgnoreCase(dataType)) return BATCH_ATTENDANCE;
        return BATCH_DEFAULT;
    }

    /**
     * 应用并发请求前随机抖动（降低QPS峰值）
     */
    public void applyJitter() {
        int jitter = JITTER_MIN_MS + ThreadLocalRandom.current().nextInt(JITTER_MAX_MS - JITTER_MIN_MS + 1);
        try {
            TimeUnit.MILLISECONDS.sleep(jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 应用批次间冷却延迟（按数据类型分级）
     */
    public void applyInterBatchDelay(String dataType) {
        int minDelay, maxDelay;
        if ("PERSON".equalsIgnoreCase(dataType)) {
            minDelay = INTER_BATCH_MIN_PERSON;
            maxDelay = INTER_BATCH_MAX_PERSON;
        } else if ("PAYROLL".equalsIgnoreCase(dataType)) {
            minDelay = INTER_BATCH_MIN_PAYROLL;
            maxDelay = INTER_BATCH_MAX_PAYROLL;
        } else if ("ATTENDANCE".equalsIgnoreCase(dataType)) {
            minDelay = INTER_BATCH_MIN_ATTENDANCE;
            maxDelay = INTER_BATCH_MAX_ATTENDANCE;
        } else {
            minDelay = INTER_BATCH_MIN_DEFAULT;
            maxDelay = INTER_BATCH_MAX_DEFAULT;
        }
        int delay = minDelay + ThreadLocalRandom.current().nextInt(maxDelay - minDelay + 1);
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 兼容旧调用 */
    public void applyInterBatchDelay() {
        applyInterBatchDelay("DEFAULT");
    }

    // ==================== 风控冷却管理 ====================

    /**
     * 记录风控触发，计算指数退避冷却时间
     * @return 本次冷却时长（毫秒）
     */
    public long recordAntiCrawlerTrigger(String account) {
        antiCrawlerTriggerTime.put(account, System.currentTimeMillis());
        int count = antiCrawlerTriggerCount
                .computeIfAbsent(account, k -> new AtomicInteger(0))
                .incrementAndGet();
        // 线性退避: base * count，上限 MAX_COOLDOWN（5min→10min→15min→...→30min封顶）
        long cooldown = ANTI_CRAWLER_BASE_COOLDOWN_MS * count;
        cooldown = Math.min(cooldown, ANTI_CRAWLER_MAX_COOLDOWN_MS);
        log.warn("账号[{}]第{}次触发风控，冷却{}ms（" + (cooldown / 1000) + "s）",
                account, count, cooldown);
        return cooldown;
    }

    /**
     * 检查风控冷却是否仍在生效
     */
    public boolean isAntiCrawlerCooldownActive(String account) {
        Long triggerTime = antiCrawlerTriggerTime.get(account);
        if (triggerTime == null) return false;
        long cooldownMs = getAntiCrawlerCooldownMs(account);
        long elapsed = System.currentTimeMillis() - triggerTime;
        return elapsed < cooldownMs;
    }

    /**
     * 获取剩余冷却时间（毫秒），0表示已冷却完成
     */
    public long getAntiCrawlerCooldownRemainingMs(String account) {
        Long triggerTime = antiCrawlerTriggerTime.get(account);
        if (triggerTime == null) return 0;
        long cooldownMs = getAntiCrawlerCooldownMs(account);
        long elapsed = System.currentTimeMillis() - triggerTime;
        return Math.max(0, cooldownMs - elapsed);
    }

    /**
     * 获取风控已持续时长（毫秒）
     */
    public long getAntiCrawlerElapsedMs(String account) {
        Long triggerTime = antiCrawlerTriggerTime.get(account);
        if (triggerTime == null) return 0;
        return System.currentTimeMillis() - triggerTime;
    }

    /**
     * 计算风控冷却时长（毫秒）
     */
    public long getAntiCrawlerCooldownMs(String account) {
        int count = antiCrawlerTriggerCount
                .computeIfAbsent(account, k -> new AtomicInteger(1)).get();
        long cooldown = ANTI_CRAWLER_BASE_COOLDOWN_MS * count;
        return Math.min(cooldown, ANTI_CRAWLER_MAX_COOLDOWN_MS);
    }

    /**
     * 重置风控状态（探针探测成功后调用）
     */
    public void resetAntiCrawlerState(String account) {
        long elapsed = getAntiCrawlerElapsedMs(account);
        int count = antiCrawlerTriggerCount
                .computeIfAbsent(account, k -> new AtomicInteger(0)).get();
        log.info("账号[{}]风控已解除，本次风控持续{}s，累计触发{}次",
                account, elapsed / 1000, count);
        antiCrawlerTriggerTime.remove(account);
        antiCrawlerTriggerCount.remove(account);
        // 重置动态退避计数器
        successStreak.remove(account);
        failStreak.remove(account);
    }

    // ==================== 动态退避 ====================

    /**
     * 根据账号历史表现动态调整延迟
     */
    private long applyDynamicBackoff(String account, int baseDelay) {
        if (account == null) {
            return baseDelay;
        }
        int fails = failStreak.computeIfAbsent(account, k -> new AtomicInteger(0)).get();
        int successes = successStreak.computeIfAbsent(account, k -> new AtomicInteger(0)).get();

        if (fails > 0) {
            // 有失败记录 → 指数增长
            double multiplier = Math.pow(BACKOFF_BASE, Math.min(fails, 5));
            long delay = (long) (baseDelay * multiplier);
            return Math.min(delay, MAX_BACKOFF_MS);
        }
        if (successes > 3) {
            // 连续成功超过3次 → 逐步缩减延迟（不低于最小延迟）
            double multiplier = Math.pow(BACKOFF_DECAY, Math.min(successes - 3, 5));
            long delay = (long) (baseDelay * multiplier);
            return Math.max(delay, MIN_DELAY_MS);
        }
        return baseDelay;
    }

    /**
     * 记录一次成功请求
     */
    public void recordSuccess(String account) {
        if (account == null) return;
        successStreak.computeIfAbsent(account, k -> new AtomicInteger(0)).incrementAndGet();
        // 成功后重置失败计数器（渐进恢复）
        AtomicInteger fails = failStreak.computeIfAbsent(account, k -> new AtomicInteger(0));
        if (fails.get() > 0) {
            fails.decrementAndGet();
        }
    }

    /**
     * 记录一次失败请求
     */
    public void recordFailure(String account) {
        if (account == null) return;
        failStreak.computeIfAbsent(account, k -> new AtomicInteger(0)).incrementAndGet();
        // 失败后重置成功计数器
        successStreak.computeIfAbsent(account, k -> new AtomicInteger(0)).set(0);
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否为反爬虫/风控消息
     */
    public boolean isAntiCrawlerMessage(String msg) {
        if (msg == null) return false;
        for (String keyword : ANTI_CRAWLER_KEYWORDS) {
            if (msg.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.renhe.di.attendancepoll.store;

import com.renhe.di.attendancepoll.config.AttendancePollProperties;
import com.renhe.di.attendancepoll.model.PollProgress;
import com.renhe.di.attendancepoll.model.PollStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 考勤采集进度 Redis 存储
 * <p>
 * 每个项目使用一个 Hash Key {@code attendance:poll:{projectNum}}，
 * 所有进度字段存储在同一 Hash 中，减少 Key 数量并支持原子性读写。
 */
@Slf4j
@Component
public class PollProgressStore {

    /** Hash 内部字段名 */
    private static final String F_STATUS = "status";
    private static final String F_CURRENT_PAGE = "current_page";
    private static final String F_TOTAL_PAGES = "total_pages";
    private static final String F_ROUND = "round";
    private static final String F_RATE_LIMIT_UNTIL = "rate_limit_until";
    private static final String F_TOKEN_VERSION = "token_version";
    private static final String F_GLOBAL_TOTAL = "global_total";
    private static final String F_TOTAL_COUNT = "total_count";
    private static final String F_SUCCESS_COUNT = "success_count";
    private static final String F_FAIL_COUNT = "fail_count";

    /** Hash Key 默认 TTL（7天，防止僵尸项目数据永久残留） */
    private static final long KEY_TTL_DAYS = 7;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AttendancePollProperties properties;

    // =====================================================================
    // Hash Key 工具
    // =====================================================================

    private String hashKey(String projectNum) {
        return properties.getProgressKeyPrefix() + projectNum;
    }

    // =====================================================================
    // 全量读写
    // =====================================================================

    /**
     * 读取项目完整进度快照
     *
     * @return PollProgress；若 Redis 无数据则返回 IDLE 初始快照
     */
    public PollProgress load(String projectNum) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey(projectNum));
        if (entries.isEmpty()) {
            return PollProgress.builder()
                    .projectNum(projectNum)
                    .status(PollStatus.IDLE)
                    .round(0)
                    .build();
        }

        return PollProgress.builder()
                .projectNum(projectNum)
                .status(parseStatus(str(entries, F_STATUS)))
                .currentPage(parseInt(entries, F_CURRENT_PAGE))
                .totalPages(parseInt(entries, F_TOTAL_PAGES))
                .round(parseInt(entries, F_ROUND))
                .rateLimitUntilMs(parseLong(entries, F_RATE_LIMIT_UNTIL))
                .tokenVersion(str(entries, F_TOKEN_VERSION))
                .globalTotal(parseInt(entries, F_GLOBAL_TOTAL))
                .totalCount(parseInt(entries, F_TOTAL_COUNT))
                .successCount(parseInt(entries, F_SUCCESS_COUNT))
                .failCount(parseInt(entries, F_FAIL_COUNT))
                .build();
    }

    /**
     * 将完整进度快照写入 Redis（全量覆盖）
     */
    public void save(PollProgress progress) {
        String key = hashKey(progress.getProjectNum());
        Map<String, String> fields = Map.ofEntries(
                Map.entry(F_STATUS, progress.getStatus().name()),
                Map.entry(F_CURRENT_PAGE, String.valueOf(progress.getCurrentPage())),
                Map.entry(F_TOTAL_PAGES, String.valueOf(progress.getTotalPages())),
                Map.entry(F_ROUND, String.valueOf(progress.getRound())),
                Map.entry(F_RATE_LIMIT_UNTIL, String.valueOf(progress.getRateLimitUntilMs())),
                Map.entry(F_TOKEN_VERSION, safe(progress.getTokenVersion())),
                Map.entry(F_GLOBAL_TOTAL, String.valueOf(progress.getGlobalTotal())),
                Map.entry(F_TOTAL_COUNT, String.valueOf(progress.getTotalCount())),
                Map.entry(F_SUCCESS_COUNT, String.valueOf(progress.getSuccessCount())),
                Map.entry(F_FAIL_COUNT, String.valueOf(progress.getFailCount()))
        );
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
    }

    // =====================================================================
    // 单字段快速读写（减少网络往返）
    // =====================================================================

    public void saveStatus(String projectNum, PollStatus status) {
        putField(projectNum, F_STATUS, status.name());
    }

    public PollStatus getStatus(String projectNum) {
        String v = getField(projectNum, F_STATUS);
        return v != null ? parseStatus(v) : PollStatus.IDLE;
    }

    public void saveCurrentPage(String projectNum, int page) {
        putField(projectNum, F_CURRENT_PAGE, String.valueOf(page));
    }

    public int getCurrentPage(String projectNum) {
        return parseInt(getField(projectNum, F_CURRENT_PAGE));
    }

    public void saveTotalPages(String projectNum, int pages) {
        putField(projectNum, F_TOTAL_PAGES, String.valueOf(pages));
    }

    public int getTotalPages(String projectNum) {
        return parseInt(getField(projectNum, F_TOTAL_PAGES));
    }

    public void saveRound(String projectNum, int round) {
        putField(projectNum, F_ROUND, String.valueOf(round));
    }

    public int getRound(String projectNum) {
        return parseInt(getField(projectNum, F_ROUND));
    }

    public void saveTokenVersion(String projectNum, String version) {
        putField(projectNum, F_TOKEN_VERSION, version);
    }

    public String getTokenVersion(String projectNum) {
        return getField(projectNum, F_TOKEN_VERSION);
    }

    public void saveRateLimitUntil(String projectNum, long untilMs) {
        putField(projectNum, F_RATE_LIMIT_UNTIL, String.valueOf(untilMs));
    }

    public long getRateLimitUntil(String projectNum) {
        return parseLong(getField(projectNum, F_RATE_LIMIT_UNTIL));
    }

    public void saveGlobalTotal(String projectNum, int total) {
        putField(projectNum, F_GLOBAL_TOTAL, String.valueOf(total));
    }

    public void addTotalCount(String projectNum, int delta) {
        incrementField(projectNum, F_TOTAL_COUNT, delta);
    }

    public void addSuccessCount(String projectNum, int delta) {
        incrementField(projectNum, F_SUCCESS_COUNT, delta);
    }

    public void addFailCount(String projectNum, int delta) {
        incrementField(projectNum, F_FAIL_COUNT, delta);
    }

    // =====================================================================
    // 清理
    // =====================================================================

    /**
     * 清理单个项目的全部进度数据（慎用）
     */
    public void clearProject(String projectNum) {
        redisTemplate.delete(hashKey(projectNum));
        log.info("项目【{}】考勤采集进度已清理", projectNum);
    }

    /**
     * 重置项目状态为 IDLE（保留轮次等历史数据，仅重置状态和计数器）
     */
    public void resetForNextRound(String projectNum) {
        String key = hashKey(projectNum);
        redisTemplate.opsForHash().putAll(key, Map.of(
                F_STATUS, PollStatus.IDLE.name(),
                F_CURRENT_PAGE, "0",
                F_TOTAL_PAGES, "0",
                F_GLOBAL_TOTAL, "0",
                F_TOTAL_COUNT, "0",
                F_SUCCESS_COUNT, "0",
                F_FAIL_COUNT, "0"
        ));
        refreshTtl(key);
    }

    /**
     * 风控恢复重置：保留 currentPage 断点，仅重置状态和计数器
     * <p>
     * 用于 RATE_LIMITED 冷却到期后的续传场景，避免丢失页码断点。
     */
    public void resetStatusForRetry(String projectNum) {
        String key = hashKey(projectNum);
        redisTemplate.opsForHash().putAll(key, Map.of(
                F_STATUS, PollStatus.IDLE.name(),
                F_TOTAL_COUNT, "0",
                F_SUCCESS_COUNT, "0",
                F_FAIL_COUNT, "0"
        ));
        refreshTtl(key);
    }

    // =====================================================================
    // 内部工具方法
    // =====================================================================

    private void putField(String projectNum, String field, String value) {
        String key = hashKey(projectNum);
        redisTemplate.opsForHash().put(key, field, value);
        refreshTtl(key);
    }

    private String getField(String projectNum, String field) {
        Object v = redisTemplate.opsForHash().get(hashKey(projectNum), field);
        return v != null ? v.toString() : null;
    }

    private void incrementField(String projectNum, String field, int delta) {
        redisTemplate.opsForHash().increment(hashKey(projectNum), field, delta);
    }

    private void refreshTtl(String key) {
        redisTemplate.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
    }

    // =====================================================================
    // 类型转换工具
    // =====================================================================

    private String str(Map<Object, Object> map, String field) {
        Object v = map.get(field);
        return v != null ? v.toString() : null;
    }

    private int parseInt(Map<Object, Object> map, String field) {
        return parseInt(str(map, field));
    }

    private int parseInt(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return 0;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(Map<Object, Object> map, String field) {
        return parseLong(str(map, field));
    }

    private long parseLong(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private PollStatus parseStatus(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return PollStatus.IDLE;
        try {
            return PollStatus.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warn("无法解析 PollStatus: {}，回退到 IDLE", v);
            return PollStatus.IDLE;
        }
    }

    private String safe(String v) {
        return v != null ? v : "";
    }
}

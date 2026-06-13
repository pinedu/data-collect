package com.renhe.di.attendancepoll.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单项目考勤采集进度快照
 * <p>
 * 对应 Redis Hash {@code attendance:poll:{projectNum}} 的全字段映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollProgress {

    /** 项目编号 */
    private String projectNum;

    /** 当前状态 */
    private PollStatus status;

    /** 当前页码（断点续传用） */
    private int currentPage;

    /** 总页数 */
    private int totalPages;

    /** 当前轮次（每完成一轮 +1） */
    private int round;

    /** 风控冷却截止时间戳（毫秒） */
    private long rateLimitUntilMs;

    /** Token 版本号（外部写入，Watcher 监听变化） */
    private String tokenVersion;

    /** 全局探针总量（第三方 total） */
    private int globalTotal;

    /** 本轮累计采集条数 */
    private int totalCount;

    /** 本轮累计成功条数 */
    private int successCount;

    /** 本轮累计失败条数 */
    private int failCount;
}

package com.renhe.di.attendancepoll.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 考勤持续采集配置
 * <p>
 * 通过 {@code attendance-poll.*} 配置项绑定，支持环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "attendance-poll")
public class AttendancePollProperties {

    /** 线程池核心并发数（同时采集的项目数） */
    private int poolCoreSize = 8;

    /** 线程池最大并发数 */
    private int poolMaxSize = 10;

    /** 一轮采集完成后，等待下一轮的间隔（秒） */
    private int pollIntervalSeconds = 30;

    /** 风控冷却默认时长（秒），首次触发 5 分钟，后续线性递增 */
    private int rateLimitCooldownSeconds = 300;

    /** 分页大小（每页条数） */
    private int pageSize = 100;

    /** Token 版本号 Redis Key 前缀 */
    private String tokenVersionKeyPrefix = "attendance:poll:token_version:";

    /** 采集进度 Redis Hash Key 前缀 */
    private String progressKeyPrefix = "attendance:poll:";
}

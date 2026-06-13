package com.renhe.di.schedule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据同步流水线配置
 * <p>
 * 通过 {@code pipeline.*} 配置项绑定，支持环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /** 项目每轮同步完成后的间隔时间（分钟），默认60分钟 */
    private int roundIntervalMinutes = 60;
}

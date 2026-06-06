package com.renhe.di.ai.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话记忆定时清理任务
 * <p>
 * 每天凌晨 3 点清理 30 天未更新的僵尸会话，
 * 防止 spring_ai_chat_memory 表无限膨胀。
 * <p>
 * 注意：timestamp 列的默认值是 CURRENT_TIMESTAMP，
 * JdbcChatMemoryRepository 每次写会话会 DELETE + INSERT，timestamp 自动更新。
 */
@Slf4j
@Component
public class ChatMemoryCleanupTask {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 每天凌晨 3:00 执行
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanStaleConversations() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM spring_ai_chat_memory WHERE timestamp < DATE_SUB(NOW(), INTERVAL 30 DAY)"
            );
            if (deleted > 0) {
                log.info("清理过期会话记忆完成, 删除 {} 条记录", deleted);
            }
        } catch (Exception e) {
            log.error("清理过期会话记忆失败", e);
        }
    }
}

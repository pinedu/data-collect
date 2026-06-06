package com.renhe.di.wechat.dto;

import lombok.Data;

import java.util.List;

/**
 * 消息发送请求
 */
@Data
public class MessageSendDTO {
    /**
     * Bot 数据库 ID
     */
    private Long botId;

    /**
     * 目标微信用户 ID（单个）
     */
    private String targetUserId;

    /**
     * 目标微信用户 ID 列表（批量）
     */
    private List<String> targetUserIds;

    /**
     * 消息类型：TEXT / IMAGE / FILE
     */
    private String messageType;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 计划发送时间（定时任务，可选）
     */
    private String scheduledAt;

    /**
     * Cron 表达式（定时重复发送，可选）
     */
    private String cronExpression;
}

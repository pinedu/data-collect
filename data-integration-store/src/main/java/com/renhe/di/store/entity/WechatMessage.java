package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 微信消息记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wechat_message")
public class WechatMessage extends BaseEntity {

    private Long id;

    /**
     * 关联 Bot ID
     */
    private Long botId;

    /**
     * 目标微信用户 ID
     */
    private String targetUserId;

    /**
     * 消息类型：TEXT / IMAGE / FILE
     */
    private String messageType;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 状态：PENDING / SENT / FAILED
     */
    private String status;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 计划发送时间
     */
    private LocalDateTime scheduledAt;

    /**
     * 实际发送时间
     */
    private LocalDateTime sentAt;
}

package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 微信Bot实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wechat_bot")
public class WechatBot extends BaseEntity {

    private Long id;

    /**
     * iLink Bot 唯一标识
     */
    private String botId;

    /**
     * Bot 名称/备注
     */
    private String botName;

    /**
     * 绑定用户 ID
     */
    private Long userId;

    /**
     * 审核状态：PENDING / APPROVED / REJECTED
     */
    private String status;

    /**
     * iLink Bot Token（加密存储）
     */
    private String botToken;

    /**
     * 推送上下文 Token
     */
    private String contextToken;

    /**
     * iLink 用户 ID（扫码者的 ilink_user_id，格式 xxx@im.wechat）
     */
    private String ilinkUserId;

    /**
     * getupdates 长轮询游标（不透明 blob，用于下次轮询）
     */
    private String getUpdatesBuf;

    /**
     * iLink API 地址
     */
    private String baseUrl;

    /**
     * Token 过期时间
     */
    private LocalDateTime tokenExpireAt;

    /**
     * 审核人 ID
     */
    private Long approvedBy;

    /**
     * 审核时间
     */
    private LocalDateTime approvedAt;
}

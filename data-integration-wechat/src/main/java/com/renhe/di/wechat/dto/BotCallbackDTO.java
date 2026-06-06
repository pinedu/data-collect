package com.renhe.di.wechat.dto;

import lombok.Data;

/**
 * 扫码回调请求
 */
@Data
public class BotCallbackDTO {
    /**
     * iLink Bot 唯一标识
     */
    private String botId;

    /**
     * Bot Token
     */
    private String botToken;

    /**
     * 微信用户 ID
     */
    private String userId;

    /**
     * 微信用户 OpenID
     */
    private String openId;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * iLink API 地址
     */
    private String baseUrl;

    /**
     * 临时票据
     */
    private String ticket;
}

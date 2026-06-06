package com.renhe.di.wechat.dto;

import lombok.Data;

/**
 * 扫码状态检查响应
 */
@Data
public class ScanStatusResponse {
    /**
     * 状态：wait / scaned / confirmed / expired
     */
    private String status;

    /**
     * 扫码成功后的回调数据
     */
    private BotCallbackDTO botInfo;

    /**
     * 扫码成功后返回的 JWT Token
     */
    private String token;
}

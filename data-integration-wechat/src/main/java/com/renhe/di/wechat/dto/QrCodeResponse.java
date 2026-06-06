package com.renhe.di.wechat.dto;

import lombok.Data;

/**
 * 二维码生成响应
 */
@Data
public class QrCodeResponse {
    /**
     * 二维码图片 Base64 编码
     */
    private String qrCodeBase64;

    /**
     * 临时票据，用于轮询扫码状态
     */
    private String ticket;

    /**
     * 过期时间（秒）
     */
    private int expireSeconds;
}

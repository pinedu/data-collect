package com.renhe.di.wechat.dto;

import lombok.Data;

/**
 * Bot 审核请求
 */
@Data
public class BotApproveDTO {
    /**
     * Bot 数据库 ID
     */
    private Long botId;

    /**
     * 操作：APPROVE / REJECT
     */
    private String action;

    /**
     * 拒绝原因
     */
    private String reason;
}

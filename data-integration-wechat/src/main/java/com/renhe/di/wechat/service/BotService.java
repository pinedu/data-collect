package com.renhe.di.wechat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.WechatBot;
import com.renhe.di.wechat.dto.BotApproveDTO;
import com.renhe.di.wechat.dto.BotCallbackDTO;
import com.renhe.di.wechat.dto.QrCodeResponse;
import com.renhe.di.wechat.dto.ScanStatusResponse;

/**
 * 微信Bot服务
 */
public interface BotService extends IService<WechatBot> {

    /**
     * 生成登录二维码
     *
     * @return 二维码响应
     */
    QrCodeResponse generateQrCode();

    /**
     * 检查扫码状态
     *
     * @param qrcode 二维码轮询令牌
     * @return 扫码状态
     */
    ScanStatusResponse checkScanStatus(String qrcode);

    /**
     * 处理扫码回调，绑定 Bot 到用户
     *
     * @param callbackDTO 回调数据
     * @return Bot 实体和 JWT Token
     */
    ScanStatusResponse handleCallback(BotCallbackDTO callbackDTO);

    /**
     * 审核 Bot（APPROVE / REJECT）
     *
     * @param dto        审核请求
     * @param approverId 审核人 ID
     */
    void approveBot(BotApproveDTO dto, Long approverId);

    /**
     * 根据 Bot ID 查询
     */
    WechatBot getByBotId(String botId);
}

package com.renhe.di.wechat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.WechatMessage;
import com.renhe.di.wechat.dto.MessageSendDTO;

/**
 * 微信消息服务
 */
public interface MessageService extends IService<WechatMessage> {

    /**
     * 发送消息（单条或批量）
     *
     * @param dto 消息发送请求
     * @return 发送成功的消息数量
     */
    int sendMessage(MessageSendDTO dto);

    /**
     * 发送待发送的定时消息（由 Scheduler 调用）
     */
    void sendPendingMessages();
}

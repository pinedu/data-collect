package com.renhe.di.wechat.scheduler;

import com.renhe.di.wechat.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息推送定时任务
 */
@Slf4j
@Component
public class MessagePushScheduler {

    @Resource
    private MessageService messageService;

    /**
     * 每 5 分钟扫描并发送待发送的定时消息
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void sendScheduledMessages() {
        log.debug("定时消息推送任务开始");
        try {
            messageService.sendPendingMessages();
        } catch (Exception e) {
            log.error("定时消息推送任务异常", e);
        }
    }
}

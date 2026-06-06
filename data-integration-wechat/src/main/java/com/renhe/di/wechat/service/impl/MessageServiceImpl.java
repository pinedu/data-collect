package com.renhe.di.wechat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.core.util.CryptoUtil;
import com.renhe.di.store.entity.WechatBot;
import com.renhe.di.store.entity.WechatMessage;
import com.renhe.di.store.mapper.WechatMessageMapper;
import com.renhe.di.wechat.client.ILinkApiClient;
import com.renhe.di.wechat.dto.MessageSendDTO;
import com.renhe.di.wechat.enums.MessageStatus;
import com.renhe.di.wechat.service.BotService;
import com.renhe.di.wechat.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信消息服务实现
 */
@Slf4j
@Service
public class MessageServiceImpl extends ServiceImpl<WechatMessageMapper, WechatMessage> implements MessageService {

    @Resource
    private ILinkApiClient iLinkApiClient;

    @Resource
    private BotService botService;

    @Override
    @Transactional
    public int sendMessage(MessageSendDTO dto) {
        WechatBot bot = botService.getById(dto.getBotId());
        if (bot == null) {
            log.warn("Bot不存在, botId={}", dto.getBotId());
            return 0;
        }

        // 解密 Bot Token
        String decryptedToken = CryptoUtil.decrypt(bot.getBotToken());

        // 构建目标用户列表
        List<String> targetUsers = new ArrayList<>();
        if (dto.getTargetUserId() != null && !dto.getTargetUserId().isEmpty()) {
            targetUsers.add(dto.getTargetUserId());
        }
        if (dto.getTargetUserIds() != null) {
            targetUsers.addAll(dto.getTargetUserIds());
        }

        int successCount = 0;
        for (String targetUserId : targetUsers) {
            // 创建消息记录
            WechatMessage message = new WechatMessage();
            message.setBotId(dto.getBotId());
            message.setTargetUserId(targetUserId);
            message.setMessageType(dto.getMessageType() != null ? dto.getMessageType() : "TEXT");
            message.setContent(dto.getContent());
            message.setStatus(MessageStatus.PENDING.name());

            // 如果有计划发送时间，设置为定时消息
            if (dto.getScheduledAt() != null && !dto.getScheduledAt().isEmpty()) {
                try {
                    message.setScheduledAt(LocalDateTime.parse(dto.getScheduledAt(),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                } catch (Exception e) {
                    log.warn("计划时间格式错误: {}", dto.getScheduledAt());
                }
            }

            baseMapper.insert(message);

            // 如果是立即发送（没有定时时间），直接发送
            if (message.getScheduledAt() == null) {
                String contextToken = decryptContextToken(bot);
                if (contextToken == null) {
                    message.setStatus(MessageStatus.FAILED.name());
                    message.setErrorMsg("缺少context_token, 请先用微信给Bot发一条消息建立会话");
                    updateById(message);
                    continue;
                }
                boolean sent = iLinkApiClient.sendMessage(
                        decryptedToken,
                        bot.getBaseUrl(),
                        targetUserId,
                        dto.getContent(),
                        contextToken
                );

                if (sent) {
                    message.setStatus(MessageStatus.SENT.name());
                    message.setSentAt(LocalDateTime.now());
                    successCount++;
                } else {
                    message.setStatus(MessageStatus.FAILED.name());
                    message.setErrorMsg("iLink API返回失败");
                }
                updateById(message);
            }
        }

        log.info("消息发送完成, 成功={}/{}", successCount, targetUsers.size());
        return successCount;
    }

    @Override
    @Transactional
    public void sendPendingMessages() {
        List<WechatMessage> pendingMessages = baseMapper.selectPendingScheduledMessages();
        if (pendingMessages.isEmpty()) {
            return;
        }

        log.info("开始发送定时消息, 数量={}", pendingMessages.size());
        for (WechatMessage message : pendingMessages) {
            WechatBot bot = botService.getById(message.getBotId());
            if (bot == null) {
                message.setStatus(MessageStatus.FAILED.name());
                message.setErrorMsg("Bot不存在");
                updateById(message);
                continue;
            }

            String decryptedToken = CryptoUtil.decrypt(bot.getBotToken());
            String contextToken = decryptContextToken(bot);
            if (contextToken == null) {
                message.setStatus(MessageStatus.FAILED.name());
                message.setErrorMsg("缺少context_token: Bot尚未收到任何用户消息，无法主动发送。请先用微信给Bot发一条消息建立会话。");
                updateById(message);
                continue;
            }
            boolean sent = iLinkApiClient.sendMessage(
                    decryptedToken,
                    bot.getBaseUrl(),
                    message.getTargetUserId(),
                    message.getContent(),
                    contextToken
            );

            if (sent) {
                message.setStatus(MessageStatus.SENT.name());
                message.setSentAt(LocalDateTime.now());
            } else {
                message.setStatus(MessageStatus.FAILED.name());
                message.setErrorMsg("iLink API返回失败");
            }
            updateById(message);
        }
        log.info("定时消息发送完成");
    }

    /**
     * 解密 Bot 的 contextToken
     */
    private String decryptContextToken(WechatBot bot) {
        try {
            String encrypted = bot.getContextToken();
            if (encrypted == null || encrypted.isEmpty()) {
                log.warn("Bot contextToken为空, botId={}, 需要先收到一条用户消息", bot.getBotId());
                return null;
            }
            return CryptoUtil.decrypt(encrypted);
        } catch (Exception e) {
            log.error("解密contextToken失败, botId={}", bot.getBotId(), e);
            return null;
        }
    }
}

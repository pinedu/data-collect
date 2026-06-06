package com.renhe.di.wechat.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.core.util.CryptoUtil;
import com.renhe.di.store.entity.WechatBot;
import com.renhe.di.store.mapper.WechatBotMapper;
import com.renhe.di.wechat.client.ILinkApiClient;
import com.renhe.di.wechat.dto.*;
import com.renhe.di.wechat.enums.BotStatus;
import com.renhe.di.wechat.pipeline.MessagePoller;
import com.renhe.di.wechat.service.BotService;
import com.renhe.di.wechat.service.UserService;
import com.renhe.di.wechat.util.JwtUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 微信Bot服务实现
 */
@Slf4j
@Service
public class BotServiceImpl extends ServiceImpl<WechatBotMapper, WechatBot> implements BotService {

    @Resource
    private ILinkApiClient iLinkApiClient;

    @Resource
    private UserService userService;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private MessagePoller messagePoller;

    @Override
    public QrCodeResponse generateQrCode() {
        return iLinkApiClient.generateLoginQrCode();
    }

    @Override
    public ScanStatusResponse checkScanStatus(String qrcode) {
        JSONObject result = iLinkApiClient.checkLoginStatus(qrcode);
        // 协议状态值: wait / scaned / confirmed / expired
        String status = result.getStr("status", "wait");

        ScanStatusResponse response = new ScanStatusResponse();
        response.setStatus(status);

        if ("confirmed".equals(status)) {
            BotCallbackDTO botInfo = new BotCallbackDTO();
            botInfo.setBotId(result.getStr("ilink_bot_id"));
            botInfo.setBotToken(result.getStr("bot_token"));
            botInfo.setUserId(result.getStr("ilink_user_id"));
            botInfo.setOpenId(result.getStr("ilink_user_id"));  // 微信侧用 ilink_user_id 作为用户标识
            botInfo.setNickname(result.getStr("ilink_user_id")); // 昵称不直接提供，后续可通过 getupdates 获取
            botInfo.setBaseUrl(result.getStr("baseurl"));
            botInfo.setTicket(qrcode);
            response.setBotInfo(botInfo);

            // 处理绑定
            ScanStatusResponse bindResult = handleCallback(botInfo);
            response.setToken(bindResult.getToken());
        }

        return response;
    }

    @Override
    @Transactional
    public ScanStatusResponse handleCallback(BotCallbackDTO callbackDTO) {
        // 1. 创建或获取用户
        var wechatUser = userService.createOrGetUser(callbackDTO);

        // 2. 按 ilink_user_id 查询已有 Bot（同一微信用户重复扫码应更新而非新增）
        WechatBot bot = baseMapper.selectByIlinkUserId(callbackDTO.getUserId());
        if (bot == null) {
            bot = new WechatBot();
            bot.setBotId(callbackDTO.getBotId());
            bot.setBotName(callbackDTO.getNickname() != null ? callbackDTO.getNickname() + "的Bot" : "Bot-" + callbackDTO.getBotId().substring(0, 8));
            bot.setUserId(wechatUser.getId());
            bot.setStatus(BotStatus.PENDING.name());
            bot.setBaseUrl(callbackDTO.getBaseUrl());
            // 加密存储 Token
            bot.setBotToken(CryptoUtil.encrypt(callbackDTO.getBotToken()));
            // 存储扫码者的 ilink_user_id（用于后续消息发送的 to_user_id）
            bot.setIlinkUserId(callbackDTO.getUserId());
            // 注意: context_token 来自入站消息（getupdates），与 bot_token 完全不同
            // 必须等用户先给 Bot 发一条消息，bot 收到后才能获得有效的 context_token
            bot.setTokenExpireAt(LocalDateTime.now().plusHours(2));
            baseMapper.insert(bot);
            log.info("新Bot绑定成功, botId={}, ilinkUserId={}, status=PENDING", callbackDTO.getBotId(), callbackDTO.getUserId());
        } else {
            // 同一用户重新扫码：更新 bot_id、token、base_url
            bot.setBotId(callbackDTO.getBotId());
            // 加密存储 Token
            bot.setBotToken(CryptoUtil.encrypt(callbackDTO.getBotToken()));
            bot.setBaseUrl(callbackDTO.getBaseUrl());
            bot.setIlinkUserId(callbackDTO.getUserId());
            bot.setTokenExpireAt(LocalDateTime.now().plusHours(2));
            bot.setUserId(wechatUser.getId());
            // 重新扫码后 context_token 和游标失效，清空
            bot.setContextToken(null);
            bot.setGetUpdatesBuf(null);
            updateById(bot);
            log.info("Bot重新绑定(更新Token), botId={}, ilinkUserId={}", callbackDTO.getBotId(), callbackDTO.getUserId());
        }

        // 3. 生成 JWT Token
        String jwtToken = jwtUtil.generateToken(wechatUser.getId(), wechatUser.getRole(), wechatUser.getOpenId());

        ScanStatusResponse response = new ScanStatusResponse();
        response.setStatus("confirmed");
        response.setToken(jwtToken);
        BotCallbackDTO botInfo = new BotCallbackDTO();
        botInfo.setBotId(bot.getBotId());
        response.setBotInfo(botInfo);

        return response;
    }

    @Override
    @Transactional
    public void approveBot(BotApproveDTO dto, Long approverId) {
        WechatBot bot = getById(dto.getBotId());
        if (bot == null) {
            throw new RuntimeException("Bot不存在");
        }

        if (!BotStatus.PENDING.name().equals(bot.getStatus())) {
            throw new RuntimeException("Bot状态不是待审核");
        }

        if ("APPROVE".equalsIgnoreCase(dto.getAction())) {
            bot.setStatus(BotStatus.APPROVED.name());
        } else if ("REJECT".equalsIgnoreCase(dto.getAction())) {
            bot.setStatus(BotStatus.REJECTED.name());
        } else {
            throw new RuntimeException("无效的操作: " + dto.getAction());
        }

        bot.setApprovedBy(approverId);
        bot.setApprovedAt(LocalDateTime.now());
        updateById(bot);

        // 审批通过后立即启动消息长轮询
        if (BotStatus.APPROVED.name().equals(bot.getStatus())) {
            messagePoller.startPollerFor(bot);
        }

        log.info("Bot审核完成, botId={}, action={}, approverId={}", bot.getBotId(), dto.getAction(), approverId);
    }

    @Override
    public WechatBot getByBotId(String botId) {
        return baseMapper.selectByBotId(botId);
    }
}

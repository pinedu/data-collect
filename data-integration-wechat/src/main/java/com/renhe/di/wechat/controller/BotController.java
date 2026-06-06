package com.renhe.di.wechat.controller;

import com.renhe.di.core.model.Result;
import com.renhe.di.store.entity.WechatBot;
import com.renhe.di.wechat.annotation.RequireRole;
import com.renhe.di.wechat.dto.*;
import com.renhe.di.wechat.enums.UserRole;
import com.renhe.di.wechat.service.BotService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Bot 管理接口
 */
@Slf4j
@RestController
@RequestMapping("/wechat/bot")
public class BotController {

    @Resource
    private BotService botService;

    /**
     * 生成登录二维码
     */
    @PostMapping("/qrcode")
    public Result<QrCodeResponse> generateQrCode() {
        QrCodeResponse qrCode = botService.generateQrCode();
        return Result.success(qrCode);
    }

    /**
     * 检查扫码状态（前端轮询）
     */
    @GetMapping("/check-status")
    public Result<ScanStatusResponse> checkStatus(@RequestParam String ticket) {
        ScanStatusResponse status = botService.checkScanStatus(ticket);
        return Result.success(status);
    }

    /**
     * 扫码回调（iLink 服务端回调）
     */
    @PostMapping("/callback")
    public Result<ScanStatusResponse> callback(@RequestBody BotCallbackDTO callbackDTO) {
        ScanStatusResponse result = botService.handleCallback(callbackDTO);
        return Result.success(result);
    }

    /**
     * Bot 列表（分页）
     * SUPER_ADMIN 可看全部，ADMIN 可看自己的
     */
    @GetMapping("/list")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.USER})
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            @RequestParam(required = false) String status,
                                            HttpServletRequest request) {
        String currentRole = (String) request.getAttribute("currentRole");
        Long currentUserId = (Long) request.getAttribute("currentUserId");

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WechatBot> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WechatBot> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();

        // 非 SUPER_ADMIN 只能看自己的 Bot
        if (!UserRole.SUPER_ADMIN.name().equals(currentRole)) {
            wrapper.eq(WechatBot::getUserId, currentUserId);
        }

        if (status != null && !status.isEmpty()) {
            wrapper.eq(WechatBot::getStatus, status);
        }
        wrapper.orderByDesc(WechatBot::getCreatedAt);

        botService.page(page, wrapper);

        Map<String, Object> data = new HashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("size", page.getSize());
        data.put("current", page.getCurrent());
        data.put("pages", page.getPages());

        return Result.success(data);
    }

    /**
     * Bot 审核
     */
    @PostMapping("/approve")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN})
    public Result<Void> approve(@RequestBody BotApproveDTO dto, HttpServletRequest request) {
        Long approverId = (Long) request.getAttribute("currentUserId");
        botService.approveBot(dto, approverId);
        return Result.success();
    }
}

package com.renhe.di.wechat.controller;

import com.renhe.di.core.model.Result;
import com.renhe.di.store.entity.WechatMessage;
import com.renhe.di.wechat.annotation.RequireRole;
import com.renhe.di.wechat.dto.MessageSendDTO;
import com.renhe.di.wechat.enums.UserRole;
import com.renhe.di.wechat.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息管理接口
 */
@Slf4j
@RestController
@RequestMapping("/wechat/message")
public class MessageController {

    @Resource
    private MessageService messageService;

    /**
     * 发送消息
     */
    @PostMapping("/send")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN})
    public Result<Map<String, Object>> send(@RequestBody MessageSendDTO dto) {
        int successCount = messageService.sendMessage(dto);
        Map<String, Object> data = new HashMap<>();
        data.put("successCount", successCount);
        return Result.success(data);
    }

    /**
     * 消息记录列表（分页）
     */
    @GetMapping("/list")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.USER})
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            @RequestParam(required = false) Long botId,
                                            @RequestParam(required = false) String status) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WechatMessage> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WechatMessage> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();

        if (botId != null) {
            wrapper.eq(WechatMessage::getBotId, botId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(WechatMessage::getStatus, status);
        }
        wrapper.orderByDesc(WechatMessage::getCreatedAt);

        messageService.page(page, wrapper);

        Map<String, Object> data = new HashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("size", page.getSize());
        data.put("current", page.getCurrent());
        data.put("pages", page.getPages());

        return Result.success(data);
    }
}

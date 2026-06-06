package com.renhe.di.bootstrap.controller;

import com.renhe.di.ai.service.InteractService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息交互回调端点 — 处理 ClawBot 聊天中用户点击操作链接的回调
 * <p>
 * 流程：
 * 1. LLM 通过 InteractService.generateActionLink() 生成签名链接
 * 2. 链接以 Markdown [按钮文本](url) 格式插入回复消息
 * 3. 用户在 ClawBot 中点击链接 → 微信内置浏览器打开 → 请求本端点
 * 4. 验证 HMAC 签名 + 过期时间 → 执行业务逻辑 → 返回确认页面
 * <p>
 * URL 格式: GET /api/interact?action=xxx&payload=xxx&expires=timestamp&sig=HMAC
 */
@Slf4j
@RestController
@RequestMapping("/interact")
public class InteractController {

    @Resource
    private InteractService interactService;

    /**
     * 处理用户点击交互链接的回调
     * <p>
     * 返回 HTML 页面而非 JSON，因为是在微信内置浏览器中打开的
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String handle(
            @RequestParam("action") String action,
            @RequestParam(value = "payload", required = false, defaultValue = "") String payload,
            @RequestParam("expires") long expires,
            @RequestParam("sig") String sig) {

        // 第一步：验证签名（防篡改 + 防重放）
        if (!interactService.verifySignature(action, payload, expires, sig)) {
            return interactService.errorPage("链接已过期或被篡改");
        }

        // 第二步：根据 action 分发业务逻辑
        log.info("交互回调: action={}, payload={}", action, payload);

        String resultMessage;
        try {
            resultMessage = dispatchAction(action, payload);
        } catch (Exception e) {
            log.error("交互回调处理异常: action={}, payload={}", action, payload, e);
            return interactService.errorPage("服务器处理异常");
        }

        return interactService.successPage(resultMessage);
    }

    /**
     * 根据 action 分发到具体业务处理
     * <p>
     * 当前支持的 action：
     * - confirm / confirm_submit : 确认类操作
     * - cancel : 取消类操作
     * - view_detail : 查看详情
     * <p>
     * 后续可扩展更多 action 类型
     */
    private String dispatchAction(String action, String payload) {
        return switch (action) {
            case "confirm", "confirm_submit" ->
                    "操作已确认" + (payload.isEmpty() ? "" : "（" + payload + "）");

            case "cancel" ->
                    "操作已取消" + (payload.isEmpty() ? "" : "（" + payload + "）");

            case "view_detail" ->
                    "详情展示" + (payload.isEmpty() ? "" : "：可通过后续消息查询 " + payload + " 的详细信息");

            default -> {
                log.info("未识别的交互 action: {}, 返回通用确认", action);
                yield "操作已完成";
            }
        };
    }
}

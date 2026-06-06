  package com.renhe.di.ai.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 消息交互服务 — 生成可点击的签名操作链接，实现聊天消息中的"按钮"交互
 * <p>
 * 原理：LLM 调用 generateActionLink() Tool 生成带 HMAC 签名的 URL，
 * 用户点击后微信浏览器打开链接，服务端验证签名后执行业务逻辑。
 * <p>
 * 安全机制：
 * - HMAC-SHA256 签名防篡改，secret 只有服务端知道
 * - 过期时间（默认 5 分钟），防止链接被重放
 * - 签名原文 = action + "|" + payload + "|" + expires
 */
@Slf4j
@Service
public class InteractService {

    @Value("${interact.secret:${wechat.interact.secret:}}")
    private String secretConfig;

    @Value("${interact.base-url:}")
    private String baseUrlConfig;

    @Value("${interact.expire-seconds:300}")
    private int expireSeconds;

    private byte[] hmacKey;

    private static final String DEFAULT_SECRET = "Interact2025@WeChat!Secure";

    @PostConstruct
    public void init() {
        String secret = (secretConfig != null && !secretConfig.isEmpty()) ? secretConfig : DEFAULT_SECRET;
        this.hmacKey = secret.getBytes(StandardCharsets.UTF_8);
        log.info("InteractService 初始化完成, expireSeconds={}", expireSeconds);
        if (secretConfig == null || secretConfig.isEmpty()) {
            log.warn("未配置 interact.secret，使用默认密钥（生产环境请务必配置）");
        }
    }

    /**
     * 生成可点击的交互操作链接（按钮）
     * <p>
     * 使用场景：需要用户确认操作、选择选项、查看详情时，
     * 调用此工具生成带安全签名的 Markdown 链接。
     * 用户点击后会在微信内置浏览器中打开，触发服务端回调。
     *
     * @param label   按钮显示的文本（如"确认提交"、"取消"、"查看详情"）
     * @param action  操作标识（confirm=确认, cancel=取消, view_detail=查看详情）
     * @param payload 业务数据标识（如考勤记录ID、项目编号，可选）
     * @return Markdown 格式的可点击链接文本，直接拼入回复中
     */
    @Tool(description = "生成一个可点击的操作按钮链接。需要用户确认/选择时调用，如'确认提交'、'取消'、'查看详情'。返回Markdown超链接直接拼入回复")
    public String generateActionLink(
            @ToolParam(description = "按钮显示文字，如'确认提交'、'取消'、'查看详情'") String label,
            @ToolParam(description = "操作类型: confirm=确认, cancel=取消, view_detail=查看详情") String action,
            @ToolParam(description = "业务数据标识（如记录ID），可选") String payload) {
        return generateActionLinkInternal(label, action, payload);
    }

    /**
     * 生成带签名的交互操作链接（内部调用）
     *
     * @param label   按钮显示文本（如 "确认提交"）
     * @param action  操作标识（如 "confirm_submit", "cancel", "view_detail"）
     * @param payload 业务数据（如考勤记录ID），可选
     * @return Markdown 格式的可点击链接: [确认提交](https://host/api/interact?action=...&sig=...)
     */
    public String generateActionLinkInternal(String label, String action, String payload) {
        String baseUrl = getBaseUrl();
        long expires = System.currentTimeMillis() / 1000 + expireSeconds;

        // 拼接签名原文
        String signData = action + "|" + (payload != null ? payload : "") + "|" + expires;

        // HMAC-SHA256 签名
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, hmacKey);
        String sig = hmac.digestHex(signData);

        // 构建 URL
        StringBuilder url = new StringBuilder(baseUrl);
        url.append("/api/interact?action=").append(urlEncode(action));
        url.append("&expires=").append(expires);
        if (payload != null && !payload.isEmpty()) {
            url.append("&payload=").append(urlEncode(payload));
        }
        url.append("&sig=").append(sig);

        return "[" + label + "](" + url.toString() + ")";
    }

    /**
     * 验证交互请求的签名是否合法
     *
     * @param action  操作标识
     * @param payload 业务数据
     * @param expires 过期时间戳（秒）
     * @param sig     客户端传来的签名
     * @return true 表示验证通过
     */
    public boolean verifySignature(String action, String payload, long expires, String sig) {
        // 过期检查
        long now = System.currentTimeMillis() / 1000;
        if (expires < now) {
            log.warn("交互链接已过期: expires={}, now={}", expires, now);
            return false;
        }

        // 重新计算签名
        String signData = action + "|" + (payload != null ? payload : "") + "|" + expires;
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, hmacKey);
        String expectedSig = hmac.digestHex(signData);

        boolean valid = expectedSig.equalsIgnoreCase(sig);
        if (!valid) {
            log.warn("交互签名验证失败: action={}, payload={}, sig={}, expected={}",
                    action, payload, sig.substring(0, Math.min(8, sig.length())) + "...",
                    expectedSig.substring(0, 8) + "...");
        }
        return valid;
    }

    /**
     * 生成成功响应 HTML 页面
     */
    public String successPage(String message) {
        return pageTemplate("✅ " + message, "#67C23A",
                "操作已成功处理，返回聊天窗口继续对话");
    }

    /**
     * 生成失败/过期响应 HTML 页面
     */
    public String errorPage(String message) {
        return pageTemplate("链接已失效", "#F56C6C",
                message + "，请返回聊天窗口重新操作");
    }

    private String pageTemplate(String title, String color, String subtitle) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                <title>%s</title>
                <style>
                  *{margin:0;padding:0;box-sizing:border-box}
                  body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif;
                       background:#f5f7fa;display:flex;align-items:center;justify-content:center;min-height:100vh}
                  .card{background:#fff;border-radius:16px;padding:40px 32px;text-align:center;
                        box-shadow:0 4px 24px rgba(0,0,0,.08);max-width:340px;width:90%%}
                  .icon{font-size:48px;margin-bottom:16px}
                  h2{font-size:20px;color:#303133;margin-bottom:8px}
                  p{font-size:14px;color:#909399;line-height:1.6}
                  .badge{display:inline-block;margin-top:24px;padding:6px 16px;border-radius:20px;
                         font-size:13px;background:%s15;color:%s}
                </style>
                </head>
                <body>
                <div class="card">
                  <div class="icon">%s</div>
                  <h2>%s</h2>
                  <p>%s</p>
                  <span class="badge">筑采助手</span>
                </div>
                </body>
                </html>
                """.formatted(title, color, color,
                title.startsWith("✅") ? "✅" : "⏰",
                title,
                subtitle);
    }

    private String getBaseUrl() {
        if (baseUrlConfig != null && !baseUrlConfig.isEmpty()) {
            return baseUrlConfig;
        }
        // 默认本地地址
        return "http://localhost:8087";
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

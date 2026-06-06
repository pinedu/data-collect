package com.renhe.di.wechat.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.renhe.di.wechat.dto.QrCodeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * iLink Bot API HTTP 客户端
 * <p>
 * 严格遵循微信 iLink Bot API 协议规范：
 * - 基地址: https://ilinkai.weixin.qq.com/ilink/bot/
 * - 参考: https://www.wechatbot.dev/en/protocol
 */
@Slf4j
@Component
public class ILinkApiClient {

    @Value("${ilink.api.base-url:https://ilinkai.weixin.qq.com}")
    private String baseUrl;

    private static final String API_PATH = "/ilink/bot";
    private static final String CHANNEL_VERSION = "1.0.0";
    private static final SecureRandom RANDOM = new SecureRandom();

    // ==================== 登录阶段 ====================

    /**
     * 获取 Bot 登录二维码
     * <p>
     * GET /ilink/bot/get_bot_qrcode?bot_type=3
     * 返回 {qrcode: "qrc_xxx", qrcode_img_content: "https://weixin.qq.com/x/xxx"}
     *
     * @return 二维码响应（qrcode 轮询令牌 + Base64 图片）
     */
    public QrCodeResponse generateLoginQrCode() {
        String url = baseUrl + API_PATH + "/get_bot_qrcode?bot_type=3";

        try {
            HttpResponse response = HttpRequest.get(url)
                    .header("SKRouteTag", "1001")
                    .timeout(10000)
                    .execute();

            if (!response.isOk()) {
                log.error("获取二维码失败, HTTP status={}, body={}", response.getStatus(), response.body());
                return buildEmptyResponse();
            }

            JSONObject result = JSONUtil.parseObj(response.body());
            String qrcode = result.getStr("qrcode");
            String qrcodeImgUrl = result.getStr("qrcode_img_content");

            if (qrcode == null || qrcode.isEmpty()) {
                log.error("获取二维码响应缺少qrcode字段: {}", response.body());
                return buildEmptyResponse();
            }

            // iLink 返回的 qrcode_img_content 是 liteapp 网页 URL（非图片直链）
            // 需要本地生成二维码，编码内容就是该 URL，微信扫码后跳转 liteapp 完成确认
            String qrCodeBase64 = "";
            if (qrcodeImgUrl != null && !qrcodeImgUrl.isEmpty()) {
                qrCodeBase64 = generateQrCodeBase64(qrcodeImgUrl);
            }

            QrCodeResponse qrResponse = new QrCodeResponse();
            qrResponse.setQrCodeBase64(qrCodeBase64);
            qrResponse.setTicket(qrcode);  // ticket 字段存储 qrcode 轮询令牌
            qrResponse.setExpireSeconds(300);
            log.info("获取二维码成功, qrcode={}", qrcode);
            return qrResponse;

        } catch (Exception e) {
            log.error("获取二维码异常", e);
            return buildEmptyResponse();
        }
    }

    /**
     * 轮询扫码状态
     * <p>
     * GET /ilink/bot/get_qrcode_status?qrcode=xxx
     * 返回 {status: wait|scaned|confirmed|expired}
     * confirmed 时附加返回 bot_token, ilink_bot_id, ilink_user_id, baseurl
     *
     * @param qrcode 二维码轮询令牌
     * @return 原始响应 JSON
     */
    public JSONObject checkLoginStatus(String qrcode) {
        String url = baseUrl + API_PATH + "/get_qrcode_status?qrcode=" + qrcode;

        try {
            HttpResponse response = HttpRequest.get(url)
                    .header("iLink-App-ClientVersion", "1")
                    .header("SKRouteTag", "1001")
                    .timeout(35000)  // 长轮询，服务端最长挂起约 35s
                    .execute();

            if (response.isOk()) {
                return JSONUtil.parseObj(response.body());
            }
        } catch (Exception e) {
            log.debug("轮询扫码状态超时/异常, qrcode={}, error={}", qrcode, e.getMessage());
        }

        // 超时或异常视为 wait 状态
        JSONObject fallback = new JSONObject();
        fallback.set("status", "wait");
        return fallback;
    }

    // ==================== 消息发送 ====================

    /**
     * 获取 typing_ticket（用于显示「正在输入」状态）
     * <p>
     * POST /ilink/bot/getconfig
     * <p>
     * 协议字段：
     * - ilink_user_id: 目标用户 ID（必填）
     * - context_token: 上下文令牌（可选，但建议带上）
     * - base_info: { channel_version: "1.0.0" }
     * <p>
     * 返回 { ret: 0, typing_ticket: "xxx" }，有效期约 24 小时
     *
     * @param botToken     Bot Token
     * @param baseUrl      iLink API 基地址
     * @param ilinkUserId  目标用户 ID
     * @param contextToken 上下文令牌（可选）
     * @return typing_ticket，获取失败返回 null
     */
    public String getConfig(String botToken, String baseUrl, String ilinkUserId, String contextToken) {
        String url = (baseUrl != null ? baseUrl : this.baseUrl) + API_PATH + "/getconfig";

        try {
            Map<String, Object> baseInfo = new HashMap<>();
            baseInfo.put("channel_version", CHANNEL_VERSION);

            Map<String, Object> body = new HashMap<>();
            body.put("ilink_user_id", ilinkUserId);
            body.put("base_info", baseInfo);
            if (contextToken != null && !contextToken.isEmpty()) {
                body.put("context_token", contextToken);
            }

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", generateWechatUin())
                    .header("SKRouteTag", "1001")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                JSONObject result = JSONUtil.parseObj(response.body());
                String ticket = result.getStr("typing_ticket");
                if (ticket != null && !ticket.isEmpty()) {
                    log.debug("获取 typing_ticket 成功, ilinkUserId={}, length={}", ilinkUserId, ticket.length());
                    return ticket;
                }
                log.warn("getconfig 未返回 typing_ticket: ilinkUserId={}, body={}", ilinkUserId, response.body());
            } else {
                log.warn("getconfig 失败, HTTP status={}, ilinkUserId={}", response.getStatus(), ilinkUserId);
            }
        } catch (Exception e) {
            log.error("getconfig 异常: ilinkUserId={}, error={}", ilinkUserId, e.getMessage(), e);
        }
        return null;
    }

    /**
     * 发送「正在输入」状态指示
     * <p>
     * POST /ilink/bot/sendtyping
     * <p>
     * 协议字段：
     * - ilink_user_id: 目标用户 ID（注意不是 to_user_id）
     * - typing_ticket: 从 getconfig 获取
     * - status: 1=开始输入, 2=停止输入
     * - 不需要 context_token
     *
     * @param botToken     Bot Token
     * @param baseUrl      iLink API 基地址
     * @param ilinkUserId  目标用户 ID（iLink 用户标识）
     * @param typingTicket typing 票据（从 getconfig 获取）
     * @param status       1=开始输入, 2=停止输入
     * @return 是否发送成功
     */
    public boolean sendTyping(String botToken, String baseUrl, String ilinkUserId,
                               String typingTicket, int status) {
        String url = (baseUrl != null ? baseUrl : this.baseUrl) + API_PATH + "/sendtyping";

        try {
            Map<String, Object> baseInfo = new HashMap<>();
            baseInfo.put("channel_version", CHANNEL_VERSION);

            Map<String, Object> body = new HashMap<>();
            body.put("ilink_user_id", ilinkUserId);
            body.put("typing_ticket", typingTicket);
            body.put("status", status);
            body.put("base_info", baseInfo);

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", generateWechatUin())
                    .header("SKRouteTag", "1001")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                JSONObject result = JSONUtil.parseObj(response.body());
                int ret = result.getInt("ret", 0);
                if (ret == 0) {
                    log.debug("sendtyping 成功, ilinkUserId={}, status={}", ilinkUserId, status);
                    return true;
                }
                log.debug("sendtyping 返回非0 ret={}, ilinkUserId={}", ret, ilinkUserId);
            }
        } catch (Exception e) {
            log.debug("sendtyping 异常: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 主动推送消息（需要 context_token 缓存）
     * <p>
     * POST /ilink/bot/sendmessage
     *
     * @param botToken     Bot Token
     * @param baseUrl      iLink API 基地址
     * @param toUserId     目标微信用户 ID
     * @param content      消息内容
     * @param contextToken 上下文令牌
     * @return 是否发送成功
     */
    public boolean sendMessage(String botToken, String baseUrl, String toUserId,
                                String content, String contextToken) {
        String url = (baseUrl != null ? baseUrl : this.baseUrl) + API_PATH + "/sendmessage";

        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("from_user_id", "");
            msg.put("to_user_id", toUserId);
            msg.put("client_id", buildClientId());
            msg.put("message_type", 2);   // BOT
            msg.put("message_state", 2);  // FINISH
            msg.put("context_token", contextToken);

            // item_list
            Map<String, Object> textItem = new HashMap<>();
            textItem.put("type", 1);
            Map<String, String> textContent = new HashMap<>();
            textContent.put("text", content);
            textItem.put("text_item", textContent);

            msg.put("item_list", new Object[]{textItem});

            // base_info
            Map<String, Object> baseInfo = new HashMap<>();
            baseInfo.put("channel_version", CHANNEL_VERSION);

            Map<String, Object> body = new HashMap<>();
            body.put("msg", msg);
            body.put("base_info", baseInfo);

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", generateWechatUin())
                    .header("SKRouteTag", "1001")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                JSONObject result = JSONUtil.parseObj(response.body());
                // 检查业务状态：ret=0 或无 ret/errcode 字段视为成功
                int ret = result.getInt("ret", 0);
                int errcode = result.getInt("errcode", 0);
                if (ret == 0 && errcode == 0) {
                    log.info("消息发送成功, toUserId={}, ret={}", toUserId, ret);
                    return true;
                } else if (ret == -14 || errcode == -14) {
                    log.warn("会话已过期, toUserId={}, ret={}, errcode={}", toUserId, ret, errcode);
                } else {
                    log.warn("消息发送被拒绝, toUserId={}, ret={}, errcode={}, errmsg={}",
                            toUserId, ret, errcode, result.getStr("errmsg"));
                }
                return false;
            }
            log.warn("消息发送失败, HTTP status={}, body={}", response.getStatus(), response.body());
            return false;
        } catch (Exception e) {
            log.error("消息发送异常, toUserId={}", toUserId, e);
            return false;
        }
    }

    // ==================== 消息接收（长轮询） ====================

    /**
     * 长轮询接收消息
     * <p>
     * POST /ilink/bot/getupdates
     *
     * @param botToken       Bot Token
     * @param baseUrl        iLink API 基地址
     * @param getUpdatesBuf  游标（首次传空字符串）
     * @return 原始响应 JSON
     */
    public JSONObject getUpdates(String botToken, String baseUrl, String getUpdatesBuf) {
        String url = (baseUrl != null ? baseUrl : this.baseUrl) + API_PATH + "/getupdates";

        try {
            Map<String, Object> baseInfo = new HashMap<>();
            baseInfo.put("channel_version", CHANNEL_VERSION);

            Map<String, Object> body = new HashMap<>();
            body.put("get_updates_buf", getUpdatesBuf != null ? getUpdatesBuf : "");
            body.put("base_info", baseInfo);

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", generateWechatUin())
                    .header("SKRouteTag", "1001")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(40000)  // 略大于服务端 35s 超时
                    .execute();

            if (response.isOk()) {
                return JSONUtil.parseObj(response.body());
            }
        } catch (Exception e) {
            log.debug("getupdates 超时/异常: {}", e.getMessage());
        }

        JSONObject empty = new JSONObject();
        empty.set("ret", 0);
        return empty;
    }

    // ==================== 私有方法 ====================

    /**
     * 用 zxing 本地生成二维码，编码内容为 liteapp URL
     * 微信扫码后跳转到 liteapp 页面完成登录确认
     */
    private String generateQrCodeBase64(String content) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 300, 300);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("本地生成二维码失败, content={}", content, e);
            return "";
        }
    }

    /**
     * 构建 client_id（格式: prefix:timestampMillis-randomHex）
     */
    private String buildClientId() {
        long ts = System.currentTimeMillis();
        String hex = Integer.toHexString(RANDOM.nextInt(0x10000000));
        return "data-integration:" + ts + "-" + hex;
    }

    /**
     * 生成 X-WECHAT-UIN 头
     * 算法: 随机 uint32 → 十进制字符串 → Base64
     */
    private String generateWechatUin() {
        int value = RANDOM.nextInt() & 0x7FFFFFFF;  // 非负
        String decimal = String.valueOf(value);
        return Base64.getEncoder().encodeToString(decimal.getBytes(StandardCharsets.UTF_8));
    }

    private QrCodeResponse buildEmptyResponse() {
        QrCodeResponse fallback = new QrCodeResponse();
        fallback.setQrCodeBase64("");
        fallback.setTicket("");
        fallback.setExpireSeconds(0);
        return fallback;
    }
}

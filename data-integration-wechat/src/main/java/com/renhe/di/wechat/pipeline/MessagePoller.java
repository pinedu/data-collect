package com.renhe.di.wechat.pipeline;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.ai.service.OcrService;
import com.renhe.di.asr.service.AudioTranscriptionService;
import com.renhe.di.core.util.CryptoUtil;
import com.renhe.di.store.entity.WechatBot;
import com.renhe.di.store.mapper.WechatBotMapper;
import com.renhe.di.wechat.client.ILinkApiClient;
import com.renhe.di.ai.service.AiService;
import com.renhe.di.wechat.service.IdentityService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息长轮询器
 * <p>
 * 为每个已审批的 Bot 启动独立线程，持续调用 getupdates 接收微信消息。
 * 收到入站消息后提取 context_token 并持久化，使 Bot 获得主动发消息能力。
 * <p>
 * 支持的消息类型：
 * - 文本消息（type=1）：直接提取文本，调用 AI 回复
 * - 语音消息（type=5）：下载音频 → ASR 转文字 → 调用 AI 回复
 * - 其他类型：记录日志，回复"暂不支持"
 */
@Slf4j
@Component
public class MessagePoller {

    @Resource
    private ILinkApiClient iLinkApiClient;

    @Resource
    private WechatBotMapper botMapper;

    @Resource
    private AiService aiService;

    @Resource
    private AudioTranscriptionService audioTranscriptionService;

    @Resource
    private OcrService ocrService;

    @Resource
    private IdentityService identityService;

    /** 运行中的轮询线程，key=botId */
    private final Map<String, Thread> runningPollers = new ConcurrentHashMap<>();

    /** typing_ticket 缓存，key=botId:userId（typing_ticket 按用户维度，有效期约 24 小时） */
    private final Map<String, String> typingTicketCache = new ConcurrentHashMap<>();

    /** typing_ticket 过期时间缓存，key=botId:userId，value=过期时间戳 */
    private final Map<String, Long> typingTicketExpiry = new ConcurrentHashMap<>();

    private static final long TYPING_TICKET_TTL_MS = 23 * 60 * 60 * 1000; // 23小时（实际约24小时，留余量）

    /** 昵称采集状态，key=botId:fromUserId */
    private final Map<String, NicknameCollectState> nicknameStates = new ConcurrentHashMap<>();

    /** 昵称采集最大重试次数 */
    private static final int MAX_NICKNAME_ATTEMPTS = 3;

    private volatile boolean shutdown = false;

    @PostConstruct
    public void startAllPollers() {
        List<WechatBot> approvedBots = botMapper.selectApprovedBots();
        log.info("启动消息轮询, 已审批Bot数量={}", approvedBots.size());

        for (WechatBot bot : approvedBots) {
            startPollerFor(bot);
        }
    }

    @PreDestroy
    public void stopAllPollers() {
        shutdown = true;
        runningPollers.values().forEach(Thread::interrupt);
        log.info("已停止所有消息轮询");
    }

    /**
     * 为指定 Bot 启动长轮询
     */
    public void startPollerFor(WechatBot bot) {
        String botId = bot.getBotId();
        if (runningPollers.containsKey(botId)) {
            log.debug("Bot轮询已在运行, botId={}", botId);
            return;
        }

        Thread thread = Thread.ofVirtual()
                .name("wechat-poller-" + botId.substring(0, Math.min(botId.length(), 8)))
                .unstarted(() -> pollLoop(bot, botId));

        runningPollers.put(botId, thread);
        thread.start();
        log.info("启动Bot轮询, botId={}", botId);
    }

    /**
     * 停止指定 Bot 的轮询
     */
    public void stopPollerFor(String botId) {
        Thread thread = runningPollers.remove(botId);
        if (thread != null) {
            thread.interrupt();
            log.info("停止Bot轮询, botId={}", botId);
        }
    }

    // ==================== 内部 ====================

    private void pollLoop(WechatBot bot, String botId) {
        String getUpdatesBuf = bot.getGetUpdatesBuf(); // 首次为 null → 传 ""

        while (!shutdown && !Thread.currentThread().isInterrupted()) {
            try {
                String botToken = CryptoUtil.decrypt(bot.getBotToken());
                if (botToken == null) {
                    log.error("Bot Token解密失败, botId={}, 停止轮询", botId);
                    break;
                }

                JSONObject response = iLinkApiClient.getUpdates(
                        botToken,
                        bot.getBaseUrl(),
                        getUpdatesBuf
                );

                // 检查会话是否过期
                int ret = response.getInt("ret", 0);
                int errcode = response.getInt("errcode", 0);
                if (ret == -14 || errcode == -14) {
                    log.warn("Bot会话过期, botId={}, ret={}, errcode={}, 停止轮询", botId, ret, errcode);
                    break;
                }

                if (ret != 0) {
                    log.debug("getupdates 返回非0 ret={}, botId={}, 短暂等待后重试", ret, botId);
                    sleepQuietly(2000);
                    continue;
                }

                // 处理消息列表
                JSONArray msgs = response.getJSONArray("msgs");
                if (msgs != null && !msgs.isEmpty()) {
                    processIncomingMessages(bot, msgs);
                }

                // 更新游标（即使没有消息也要更新）
                String newBuf = response.getStr("get_updates_buf");
                if (newBuf != null && !newBuf.isEmpty()) {
                    getUpdatesBuf = newBuf;
                    persistUpdatesBuf(bot.getId(), newBuf);
                }

                // 立即开始下一次轮询
            } catch (Exception e) {
                log.warn("Bot轮询异常, botId={}, error={}", botId, e.getMessage());
                sleepQuietly(5000);
            }
        }

        runningPollers.remove(botId);
        log.info("Bot轮询已退出, botId={}", botId);
    }

    /**
     * 处理入站消息，提取 context_token 并持久化，然后根据消息类型自动回复
     */
    private void processIncomingMessages(WechatBot bot, JSONArray msgs) {
        for (int i = 0; i < msgs.size(); i++) {
            JSONObject msg = msgs.getJSONObject(i);
            String contextToken = msg.getStr("context_token");
            String fromUserId = msg.getStr("from_user_id");
            int messageType = msg.getInt("message_type", 0);

            log.info("收到入站消息: botId={}, fromUserId={}, messageType={}, contextToken存在={}",
                    bot.getBotId(), fromUserId, messageType, contextToken != null);

            if (contextToken != null && !contextToken.isEmpty()) {
                // 持久化 context_token（加密存储），bot 获得主动发消息能力
                String encrypted = CryptoUtil.encrypt(contextToken);
                botMapper.updateContextToken(bot.getId(), encrypted);
                bot.setContextToken(encrypted);
                log.info("更新context_token, botId={}, fromUserId={}", bot.getBotId(), fromUserId);

                // 仅回复用户消息（message_type=1），忽略 Bot 自己的消息（message_type=2）
                if (messageType == 1 && fromUserId != null) {
                    // 检测消息内容类型
                    int itemType = detectItemType(msg);

                    if (itemType == 1) {
                        // 文本消息
                        handleTextMessage(bot, msg, fromUserId, contextToken);
                    } else if (itemType == 5) {
                        // 语音消息
                        handleVoiceMessage(bot, msg, fromUserId, contextToken);
                    } else if (itemType == 2) {
                        // 图片消息
                        handleImageMessage(bot, msg, fromUserId, contextToken);
                    } else if (itemType == 4) {
                        // 文件消息
                        handleFileMessage(bot, msg, fromUserId, contextToken);
                    } else {
                        // 未知类型：记录完整 JSON 以便排查
                        log.info("收到非文本/非语音/非图片/非文件消息: itemType={}, 完整JSON={}", itemType, msg);
                        sendReply(bot, fromUserId, contextToken,
                                "暂时只支持文字、语音、图片和文件消息，请发送文字、语音、图片或文件。");
                    }
                }
            }
        }
    }

    /**
     * 处理文本消息
     */
    private void handleTextMessage(WechatBot bot, JSONObject msg, String fromUserId, String contextToken) {
        String textContent = extractText(msg);
        // 防刷：超长消息视为攻击
        if (isAbusive(textContent)) {
            log.warn("检测到疑似攻击消息(长度={}), 已静默, botId={}, fromUserId={}",
                    textContent != null ? textContent.length() : 0, bot.getBotId(), fromUserId);
            return;
        }
        autoReply(bot, fromUserId, contextToken, textContent);
    }

    /**
     * 处理语音消息：优先使用微信自带的转文字结果，没有再走本地 ASR
     */
    private void handleVoiceMessage(WechatBot bot, JSONObject msg, String fromUserId, String contextToken) {
        try {
            // 优先提取微信自带的语音识别结果（voice_item.text）
            String transcribedText = extractVoiceText(msg);

            if (transcribedText != null && !transcribedText.isEmpty()) {
                log.info("使用微信自带语音识别结果: text={}", transcribedText);
                autoReply(bot, fromUserId, contextToken, transcribedText);
                return;
            }

            // 微信没有转文字结果，降级到本地 ASR
            log.info("微信未提供转文字结果，尝试本地 ASR");
            if (!audioTranscriptionService.isAvailable()) {
                log.warn("ASR 服务未配置，无法处理语音消息, botId={}", bot.getBotId());
                sendReply(bot, fromUserId, contextToken, "语音识别功能暂未开启，请发送文字消息。");
                return;
            }

            String audioUrl = extractAudioUrl(msg);
            if (audioUrl == null || audioUrl.isEmpty()) {
                log.warn("无法提取音频URL, botId={}, msg={}", bot.getBotId(), msg);
                sendReply(bot, fromUserId, contextToken, "语音文件获取失败，请重新发送。");
                return;
            }

            log.info("下载语音文件: url={}", audioUrl);
            byte[] audioData = downloadAudio(audioUrl);
            if (audioData == null || audioData.length == 0) {
                log.warn("音频文件下载失败, url={}", audioUrl);
                sendReply(bot, fromUserId, contextToken, "语音文件下载失败，请重新发送。");
                return;
            }

            log.info("语音文件下载完成: size={}KB", audioData.length / 1024);
            String fileName = guessAudioFileName(audioUrl);
            transcribedText = audioTranscriptionService.transcribe(audioData, fileName);

            if (transcribedText == null || transcribedText.trim().isEmpty()) {
                log.warn("语音转文字结果为空, botId={}", bot.getBotId());
                sendReply(bot, fromUserId, contextToken, "未能识别语音内容，请重新发送或改用文字。");
                return;
            }

            log.info("本地语音转文字完成: text={}", transcribedText);
            autoReply(bot, fromUserId, contextToken, transcribedText);

        } catch (Exception e) {
            log.error("处理语音消息异常, botId={}", bot.getBotId(), e);
            sendReply(bot, fromUserId, contextToken, "语音处理出错，请稍后重试或改用文字。");
        }
    }

    /**
     * 提取微信自带的语音识别结果（voice_item.text）
     */
    private String extractVoiceText(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    JSONObject voiceItem = firstItem.getJSONObject("voice_item");
                    if (voiceItem != null) {
                        String text = voiceItem.getStr("text");
                        if (text != null && !text.isEmpty()) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取微信语音识别结果异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 检测消息 item_list 中的内容类型
     * <p>
     * iLink 协议 item type：
     * 1=文本, 2=图片, 3=视频, 4=文件, 5=语音
     * <p>
     * 注意：微信语音消息有时 type=3（视频），但实际包含 voice_item 字段，
     * 因此优先按字段内容判断，type 仅作参考。
     */
    private int detectItemType(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    // 优先按字段内容判断（比 type 更可靠）
                    if (firstItem.containsKey("voice_item")) {
                        return 5;
                    }
                    if (firstItem.containsKey("text_item")) {
                        return 1;
                    }
                    if (firstItem.containsKey("image_item")) {
                        return 2;
                    }
                    if (firstItem.containsKey("file_item")) {
                        return 4;
                    }
                    // 其次按 type 字段
                    int type = firstItem.getInt("type", 0);
                    if (type > 0) {
                        return type;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("检测消息类型异常: {}", e.getMessage());
        }
        // 默认视为文本（兼容旧逻辑）
        return 1;
    }

    /**
     * 从语音消息中提取音频文件 URL
     * <p>
     * 尝试多个可能的字段路径，兼容不同的 iLink 消息格式。
     */
    private String extractAudioUrl(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    JSONObject voiceItem = firstItem.getJSONObject("voice_item");
                    if (voiceItem != null) {
                        // 路径1: voice_item.media.full_url（微信语音实际格式）
                        JSONObject media = voiceItem.getJSONObject("media");
                        if (media != null) {
                            String fullUrl = media.getStr("full_url");
                            if (fullUrl != null && !fullUrl.isEmpty()) {
                                return fullUrl;
                            }
                            String url = media.getStr("url");
                            if (url != null && !url.isEmpty()) {
                                return url;
                            }
                        }
                        // 路径2: voice_item 顶层 url/file_url
                        String url = voiceItem.getStr("url");
                        if (url != null && !url.isEmpty()) {
                            return url;
                        }
                        url = voiceItem.getStr("file_url");
                        if (url != null && !url.isEmpty()) {
                            return url;
                        }
                    }
                    // 路径3: 直接在 item 上找 url
                    String url = firstItem.getStr("url");
                    if (url != null && !url.isEmpty()) {
                        return url;
                    }
                    // 路径4: file_item.url
                    JSONObject fileItem = firstItem.getJSONObject("file_item");
                    if (fileItem != null) {
                        url = fileItem.getStr("url");
                        if (url != null && !url.isEmpty()) {
                            return url;
                        }
                    }
                }
            }
            // 路径5: msg 顶层 url
            String topUrl = msg.getStr("url");
            if (topUrl != null && !topUrl.isEmpty()) {
                return topUrl;
            }
        } catch (Exception e) {
            log.debug("提取音频URL异常: {}", e.getMessage());
        }
        log.warn("未能提取音频URL，完整消息JSON: {}", msg);
        return null;
    }

    /**
     * 下载音频文件
     */
    private byte[] downloadAudio(String url) {
        try {
            HttpResponse response = HttpRequest.get(url)
                    .timeout(15000)
                    .execute();
            if (response.isOk()) {
                return response.bodyBytes();
            }
            log.warn("下载音频失败, HTTP status={}", response.getStatus());
        } catch (Exception e) {
            log.error("下载音频异常, url={}", url, e);
        }
        return null;
    }

    // ==================== 图片消息处理 ====================

    /**
     * 处理图片消息：下载图片 → base64 → 调用多模态模型理解 → AI 回复
     */
    private void handleImageMessage(WechatBot bot, JSONObject msg, String fromUserId, String contextToken) {
        try {
            // 提取图片 URL 和 AES 密钥
            ImageInfo imageInfo = extractImageInfo(msg);
            if (imageInfo == null || imageInfo.url() == null || imageInfo.url().isEmpty()) {
                log.warn("无法提取图片信息, botId={}, msg={}", bot.getBotId(), msg);
                sendReply(bot, fromUserId, contextToken, "图片获取失败，请重新发送。");
                return;
            }

            log.info("处理图片消息: url={}, hasAesKey={}", imageInfo.url(), imageInfo.aesKey() != null);

            // 下载图片（使用 Hutool，兼容微信图片鉴权）
            byte[] imageData = downloadImage(imageInfo.url());
            if (imageData == null || imageData.length == 0) {
                log.warn("图片下载失败, url={}", imageInfo.url());
                sendReply(bot, fromUserId, contextToken, "图片下载失败，请重新发送。");
                return;
            }

            log.info("图片下载完成: size={}KB", imageData.length / 1024);

            // 如果存在 AES 密钥，解密图片
            if (imageInfo.aesKey() != null && !imageInfo.aesKey().isEmpty()) {
                log.info("解密图片: aesKeyLength={}", imageInfo.aesKey().length());
                imageData = decryptImage(imageData, imageInfo.aesKey());
                if (imageData == null) {
                    log.warn("图片解密失败");
                    sendReply(bot, fromUserId, contextToken, "图片解密失败，请重新发送。");
                    return;
                }
                log.info("图片解密完成: decryptedSize={}KB", imageData.length / 1024);
            }

            // 保存到临时文件，调用 OCR 识别
            File tempFile = saveToTempFile(imageData, "image.jpg");
            if (tempFile == null) {
                log.warn("保存临时文件失败");
                sendReply(bot, fromUserId, contextToken, "图片处理失败，请重新发送。");
                return;
            }

            log.info("临时图片文件保存完成: path={}", tempFile.getAbsolutePath());

            // 调用 OCR 识别图片内容
            String imageContent;
            try {
                imageContent = ocrService.parseFile(tempFile);
            } finally {
                tempFile.delete();
            }

            if (imageContent == null || imageContent.trim().isEmpty()) {
                log.warn("OCR识别结果为空, botId={}", bot.getBotId());
                sendReply(bot, fromUserId, contextToken, "未能识别图片内容，请重新发送或改用文字描述。");
                return;
            }

            log.info("OCR识别完成: contentLength={}", imageContent.length());

            // 将 OCR 结果作为用户问题，走正常 AI 流程
            String combinedQuestion = "用户发送了一张图片，OCR识别内容如下：\n\n" + imageContent;
            autoReply(bot, fromUserId, contextToken, combinedQuestion);

        } catch (Exception e) {
            log.error("处理图片消息异常, botId={}", bot.getBotId(), e);
            sendReply(bot, fromUserId, contextToken, "图片处理出错，请稍后重试或改用文字。");
        }
    }

    /**
     * 处理文件消息（itemType=4）
     * <p>
     * 下载文件 -> 调用 OCR 解析 -> 将文本内容传给 AI 分析
     */
    private void handleFileMessage(WechatBot bot, JSONObject msg, String fromUserId, String contextToken) {
        try {
            // 提取文件信息
            FileInfo fileInfo = extractFileInfo(msg);
            if (fileInfo == null || fileInfo.url() == null || fileInfo.url().isEmpty()) {
                log.warn("无法提取文件信息, botId={}, msg={}", bot.getBotId(), msg);
                sendReply(bot, fromUserId, contextToken, "文件获取失败，请重新发送。");
                return;
            }

            log.info("处理文件消息: fileName={}, url={}, hasAesKey={}",
                    fileInfo.fileName(), fileInfo.url(), fileInfo.aesKey() != null);

            // 下载文件
            byte[] fileData = downloadFile(fileInfo.url());
            if (fileData == null || fileData.length == 0) {
                log.warn("文件下载失败, url={}", fileInfo.url());
                sendReply(bot, fromUserId, contextToken, "文件下载失败，请重新发送。");
                return;
            }

            log.info("文件下载完成: size={}KB", fileData.length / 1024);

            // 如果存在 AES 密钥，解密文件
            if (fileInfo.aesKey() != null && !fileInfo.aesKey().isEmpty()) {
                log.info("解密文件: aesKeyLength={}", fileInfo.aesKey().length());
                fileData = decryptFile(fileData, fileInfo.aesKey());
                if (fileData == null) {
                    log.warn("文件解密失败");
                    sendReply(bot, fromUserId, contextToken, "文件解密失败，请重新发送。");
                    return;
                }
                log.info("文件解密完成: decryptedSize={}KB", fileData.length / 1024);
            }

            // 保存到临时文件
            File tempFile = saveToTempFile(fileData, fileInfo.fileName());
            if (tempFile == null) {
                log.warn("保存临时文件失败");
                sendReply(bot, fromUserId, contextToken, "文件处理失败，请重新发送。");
                return;
            }

            log.info("临时文件保存完成: path={}", tempFile.getAbsolutePath());

            // 调用 OCR 解析文件内容
            String fileContent;
            try {
                fileContent = ocrService.parseFile(tempFile);
            } finally {
                tempFile.delete();
            }

            if (fileContent == null || fileContent.trim().isEmpty()) {
                log.warn("OCR解析结果为空, botId={}", bot.getBotId());
                sendReply(bot, fromUserId, contextToken, "未能识别文件内容，请确保文件包含可识别的文字。");
                return;
            }

            log.info("OCR解析完成: contentLength={}", fileContent.length());

            // 将文件内容作为用户问题，走正常 AI 流程
            String combinedQuestion = "用户发送了一个文件，内容如下：\n\n" + fileContent;
            autoReply(bot, fromUserId, contextToken, combinedQuestion);

        } catch (Exception e) {
            log.error("处理文件消息异常, botId={}", bot.getBotId(), e);
            sendReply(bot, fromUserId, contextToken, "文件处理出错，请稍后重试或改用文字。");
        }
    }

    /**
     * 从文件消息中提取文件 URL 和 AES 密钥
     */
    private FileInfo extractFileInfo(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    JSONObject fileItem = firstItem.getJSONObject("file_item");
                    if (fileItem != null) {
                        String aesKey = fileItem.getStr("aeskey");
                        String fileName = fileItem.getStr("file_name");
                        String md5 = fileItem.getStr("md5");

                        // 路径: file_item.media.full_url
                        JSONObject media = fileItem.getJSONObject("media");
                        if (media != null) {
                            String fullUrl = media.getStr("full_url");
                            if (fullUrl != null && !fullUrl.isEmpty()) {
                                return new FileInfo(fullUrl, aesKey, fileName, md5);
                            }
                            String url = media.getStr("url");
                            if (url != null && !url.isEmpty()) {
                                return new FileInfo(url, aesKey, fileName, md5);
                            }
                        }
                        // 路径: file_item 顶层 url
                        String url = fileItem.getStr("url");
                        if (url != null && !url.isEmpty()) {
                            return new FileInfo(url, aesKey, fileName, md5);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取文件信息异常: {}", e.getMessage());
        }
        log.warn("未能提取文件信息，完整消息JSON: {}", msg);
        return null;
    }

    /**
     * 文件信息（URL + AES 密钥 + 文件名 + MD5）
     */
    private record FileInfo(String url, String aesKey, String fileName, String md5) {
    }

    /**
     * 下载文件
     */
    private byte[] downloadFile(String url) {
        try {
            HttpResponse response = HttpRequest.get(url)
                    .timeout(60000)
                    .execute();
            if (response.isOk()) {
                return response.bodyBytes();
            }
            log.warn("下载文件失败, HTTP status={}", response.getStatus());
        } catch (Exception e) {
            log.error("下载文件异常, url={}", url, e);
        }
        return null;
    }

    /**
     * 解密文件（AES-128-ECB + PKCS7）
     */
    private byte[] decryptFile(byte[] encryptedData, String aesKey) {
        return decryptAesEcb(encryptedData, aesKey);
    }

    /**
     * 通用 AES-128-ECB 解密，自动识别 iLink 协议的 3 种密钥格式
     */
    private byte[] decryptAesEcb(byte[] encryptedData, String aesKeyRaw) {
        try {
            byte[] keyBytes = normalizeAesKey(aesKeyRaw);
            if (keyBytes == null) {
                log.error("AES密钥格式无法识别: length={}, preview={}",
                        aesKeyRaw != null ? aesKeyRaw.length() : 0,
                        aesKeyRaw != null ? aesKeyRaw.substring(0, Math.min(aesKeyRaw.length(), 20)) : "null");
                return null;
            }

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(encryptedData);
        } catch (Exception e) {
            log.error("AES解密失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 iLink AES 密钥统一归一化为 16 字节
     * <p>
     * 3 种格式：
     * 1. 直接 hex（32 字符，纯 [0-9a-fA-F]）→ hexToBytes
     * 2. base64(原始 16 字节)（24 字符，以 = 结尾）→ Base64 decode
     * 3. base64(hex 字符串)（44 字符，以 == 结尾）→ Base64 decode → hexToBytes
     */
    private byte[] normalizeAesKey(String key) {
        if (key == null || key.isEmpty()) return null;
        key = key.trim();

        // 格式1: 直接 hex（32 字符，纯十六进制）
        if (key.length() == 32 && key.matches("[0-9a-fA-F]+")) {
            return hexToBytes(key);
        }

        // 格式2/3: base64 编码
        if (key.endsWith("=")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(key);
                // 格式2: base64(原始16字节) → 解码后正好16字节
                if (decoded.length == 16) {
                    return decoded;
                }
                // 格式3: base64(hex字符串) → 解码后是32字符hex，再 hexToBytes
                if (decoded.length == 32) {
                    String hexStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    if (hexStr.matches("[0-9a-fA-F]+")) {
                        return hexToBytes(hexStr);
                    }
                }
            } catch (Exception e) {
                log.debug("Base64解码AES密钥失败: {}", e.getMessage());
            }
        }

        // 兜底：尝试直接 hexToBytes（容错非标准长度）
        if (key.matches("[0-9a-fA-F]+") && key.length() % 2 == 0) {
            return hexToBytes(key);
        }

        return null;
    }

    /**
     * 保存字节数据到临时文件
     */
    private File saveToTempFile(byte[] data, String fileName) {
        try {
            String suffix = "";
            if (fileName != null && fileName.contains(".")) {
                suffix = fileName.substring(fileName.lastIndexOf("."));
            }
            File tempFile = File.createTempFile("ocr_", suffix);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
            }
            return tempFile;
        } catch (Exception e) {
            log.error("保存临时文件失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从图片消息中提取图片 URL 和 AES 密钥
     */
    private ImageInfo extractImageInfo(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    JSONObject imageItem = firstItem.getJSONObject("image_item");
                    if (imageItem != null) {
                        String aesKey = imageItem.getStr("aeskey");

                        // 路径1: image_item.media.full_url
                        JSONObject media = imageItem.getJSONObject("media");
                        if (media != null) {
                            String fullUrl = media.getStr("full_url");
                            if (fullUrl != null && !fullUrl.isEmpty()) {
                                return new ImageInfo(fullUrl, aesKey);
                            }
                            String url = media.getStr("url");
                            if (url != null && !url.isEmpty()) {
                                return new ImageInfo(url, aesKey);
                            }
                        }
                        // 路径2: image_item 顶层 url
                        String url = imageItem.getStr("url");
                        if (url != null && !url.isEmpty()) {
                            return new ImageInfo(url, aesKey);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取图片信息异常: {}", e.getMessage());
        }
        log.warn("未能提取图片信息，完整消息JSON: {}", msg);
        return null;
    }

    /**
     * 图片信息（URL + AES 密钥）
     */
    private record ImageInfo(String url, String aesKey) {
    }

    /**
     * 下载图片文件
     */
    private byte[] downloadImage(String url) {
        try {
            HttpResponse response = HttpRequest.get(url)
                    .timeout(30000)
                    .execute();
            if (response.isOk()) {
                return response.bodyBytes();
            }
            log.warn("下载图片失败, HTTP status={}", response.getStatus());
        } catch (Exception e) {
            log.error("下载图片异常, url={}", url, e);
        }
        return null;
    }

    /**
     * 解密微信图片（AES-128-ECB + PKCS7）
     * <p>
     * iLink 协议 AES 密钥有 3 种格式，需自动识别：
     * - 直接 hex（32 字符，纯 [0-9a-fA-F]）
     * - base64(原始 16 字节)（24 字符，含 =/+）
     * - base64(hex 字符串)（44 字符，以 == 结尾）
     */
    private byte[] decryptImage(byte[] encryptedData, String aesKey) {
        return decryptAesEcb(encryptedData, aesKey);
    }

    /**
     * 十六进制字符串转字节数组（带输入校验）
     */
    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) {
            log.warn("hexToBytes 输入无效: length={}", hex != null ? hex.length() : 0);
            return new byte[0];
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) {
                log.warn("hexToBytes 非十六进制字符 at pos {}: '{}'", i, hex.substring(i, Math.min(i + 2, len)));
                return new byte[0];
            }
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }

    /**
     * 根据 URL 猜测图片 MIME 类型
     */
    private String guessImageMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".bmp")) return "image/bmp";
        return "image/jpeg"; // 默认
    }

    /**
     * 根据 URL 猜测音频文件名（用于 Whisper API 的 filename 参数）
     */
    private String guessAudioFileName(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".amr")) return "voice.amr";
        if (lower.contains(".ogg")) return "voice.ogg";
        if (lower.contains(".mp3")) return "voice.mp3";
        if (lower.contains(".wav")) return "voice.wav";
        if (lower.contains(".silk")) return "voice.silk";
        // 微信语音通常是 AMR 或 Silk 格式
        return "voice.ogg";
    }

    /**
     * 收到用户消息后自动回复，优先使用 AI 生成回复，AI 不可用时降级到欢迎语
     * <p>
     * 流程：身份识别 → sendtyping(1) → AI处理 → sendtyping(2) → sendmessage
     */
    private void autoReply(WechatBot bot, String toUserId, String contextToken, String textContent) {
        // ==================== 身份识别预处理 ====================
        try {
            Long wechatUserId = bot.getUserId();
            log.info("[身份识别] 进入预处理, botId={}, wechatUserId={}, fromUserId={}",
                    bot.getBotId(), wechatUserId, toUserId);
            if (wechatUserId == null) {
                log.warn("身份识别跳过: bot.userId 为空, botId={}, fromUserId={}, 可能尚未绑定用户",
                        bot.getBotId(), toUserId);
            } else {
            String stateKey = bot.getBotId() + ":" + toUserId;

            if (!identityService.hasUserName(wechatUserId)) {
                // 用户未注册昵称，进入采集流程
                NicknameCollectState state = nicknameStates.get(stateKey);

                if (state == null) {
                    // 首次进入 — 发送引导语，缓存原始问题
                    nicknameStates.put(stateKey, new NicknameCollectState(textContent, 0));
                    sendReply(bot, toUserId, contextToken, identityService.getNicknamePrompt());
                    log.info("昵称采集-发起引导, botId={}, fromUserId={}, question={}",
                            bot.getBotId(), toUserId, textContent);
                    return;
                }

                // 用户回复昵称 — 先 AI 提取名字
                String extractedName = aiService.extractNickname(textContent);

                if (extractedName == null) {
                    // AI 无法从消息中提取名字，追问
                    int attempts = state.attemptCount() + 1;
                    if (attempts >= MAX_NICKNAME_ATTEMPTS) {
                        nicknameStates.remove(stateKey);
                        sendReply(bot, toUserId, contextToken, identityService.getSkipNicknamePrompt());
                        log.info("昵称采集-AI提取连续失败，跳过, botId={}, attempts={}",
                                bot.getBotId(), attempts);
                        textContent = state.originalQuestion();
                        // 继续走 AI 流程
                    } else {
                        nicknameStates.put(stateKey, new NicknameCollectState(state.originalQuestion(), attempts));
                        sendReply(bot, toUserId, contextToken, identityService.getExtractionFailedPrompt());
                        log.info("昵称采集-AI提取失败, botId={}, input={}, attempts={}",
                                bot.getBotId(), textContent, attempts);
                        return;
                    }
                } else {
                    // AI 成功提取名字，再进行格式校验
                    String validated = identityService.validateNickname(extractedName);
                    if (validated == null) {
                        int attempts = state.attemptCount() + 1;
                        if (attempts >= MAX_NICKNAME_ATTEMPTS) {
                            nicknameStates.remove(stateKey);
                            sendReply(bot, toUserId, contextToken, identityService.getSkipNicknamePrompt());
                            log.info("昵称采集-格式校验连续失败，跳过, botId={}, attempts={}",
                                    bot.getBotId(), attempts);
                            textContent = state.originalQuestion();
                            // 继续走 AI 流程
                        } else {
                            nicknameStates.put(stateKey, new NicknameCollectState(state.originalQuestion(), attempts));
                            sendReply(bot, toUserId, contextToken, identityService.getInvalidNicknamePrompt());
                            log.info("昵称采集-格式校验失败, botId={}, extracted={}, attempts={}",
                                    bot.getBotId(), extractedName, attempts);
                            return;
                        }
                } else {
                    // 校验通过 — 保存昵称，发送欢迎语，继续处理原始问题
                    String welcome = identityService.saveAndBuildWelcome(wechatUserId, validated);
                    if (welcome != null) {
                        nicknameStates.remove(stateKey);
                        sendReply(bot, toUserId, contextToken, welcome);
                        log.info("昵称采集-保存成功, botId={}, userName={}", bot.getBotId(), validated);
                        textContent = state.originalQuestion();
                        // 继续走 AI 流程（不 return）
                    } else {
                        // 保存失败 — 保留状态，提示用户重试
                        int attempts = state.attemptCount() + 1;
                        if (attempts >= MAX_NICKNAME_ATTEMPTS) {
                            nicknameStates.remove(stateKey);
                            sendReply(bot, toUserId, contextToken, identityService.getSkipNicknamePrompt());
                            log.warn("昵称采集-DB保存连续失败，跳过, botId={}, attempts={}",
                                    bot.getBotId(), attempts);
                            textContent = state.originalQuestion();
                            // 继续走 AI 流程
                        } else {
                            nicknameStates.put(stateKey, new NicknameCollectState(state.originalQuestion(), attempts));
                            sendReply(bot, toUserId, contextToken,
                                    "\uD83D\uDE05 信息保存时遇到了点小问题，麻烦再告诉我一次您的称呼可以吗？");
                            log.warn("昵称采集-DB保存失败，重试, botId={}, attempts={}",
                                    bot.getBotId(), attempts);
                            return;
                        }
                    }
                }
                }
            }
            }
        } catch (Exception e) {
            log.error("身份识别预处理异常, botId={}, fromUserId={}", bot.getBotId(), toUserId, e);
        }
        // ==================== 正常 AI 回复流程 ====================
        try {
            String botToken = CryptoUtil.decrypt(bot.getBotToken());
            if (botToken == null) {
                log.warn("自动回复失败: Bot Token解密失败, botId={}", bot.getBotId());
                return;
            }

            // 发送「正在输入」状态
            sendTypingIndicator(bot, botToken, toUserId, contextToken, 1);

            // 优先使用 AI 生成回复
            String replyText = null;
            try {
                if (aiService != null && textContent != null && !textContent.trim().isEmpty()) {
                    // conversationId = botId:fromUserId，每个微信用户独立会话记忆
                    String conversationId = bot.getBotId() + ":" + toUserId;
                    replyText = aiService.handle(conversationId, textContent);
                }
            } catch (Exception e) {
                log.warn("AI 回复生成异常, 降级到欢迎语, botId={}, error={}", bot.getBotId(), e.getMessage());
            }

            // 停止「正在输入」状态
            sendTypingIndicator(bot, botToken, toUserId, contextToken, 2);

            // AI 返回空时降级 — 简洁告知职责范围，不过度回复
            if (replyText == null || replyText.trim().isEmpty()) {
                replyText = "我是筑采助手，可以帮您查询建筑工地的考勤、人员、班组和项目数据。请直接告诉我您想了解什么。";
            }

            sendReply(bot, toUserId, contextToken, replyText);

        } catch (Exception e) {
            log.error("自动回复异常, botId={}", bot.getBotId(), e);
        }
    }

    /**
     * 发送「正在输入」状态指示器
     *
     * @param ilinkUserId 目标用户 ID（from_user_id）
     * @param contextToken 上下文令牌
     * @param status 1=开始输入, 2=停止输入
     */
    private void sendTypingIndicator(WechatBot bot, String botToken, String ilinkUserId,
                                      String contextToken, int status) {
        try {
            String botId = bot.getBotId();
            String typingTicket = getOrFetchTypingTicket(botId, botToken, bot.getBaseUrl(), ilinkUserId, contextToken);

            if (typingTicket == null) {
                log.warn("typing_ticket 不可用，跳过输入状态指示, botId={}", botId);
                return;
            }

            iLinkApiClient.sendTyping(botToken, bot.getBaseUrl(), ilinkUserId, typingTicket, status);
            log.info("sendtyping 已发送: botId={}, ilinkUserId={}, status={}", bot.getBotId(),
                    ilinkUserId.length() > 20 ? ilinkUserId.substring(0, 20) + "..." : ilinkUserId, status);
        } catch (Exception e) {
            // typing 指示器失败不影响主流程，但记录警告以便排查
            log.warn("sendtyping 失败: botId={}, status={}, error={}", bot.getBotId(), status, e.getMessage());
        }
    }

    /**
     * 获取或刷新 typing_ticket（按用户维度缓存，23小时有效期）
     */
    private String getOrFetchTypingTicket(String botId, String botToken, String baseUrl,
                                           String ilinkUserId, String contextToken) {
        String cacheKey = botId + ":" + ilinkUserId;
        Long expiry = typingTicketExpiry.get(cacheKey);
        String cached = typingTicketCache.get(cacheKey);

        // 缓存有效则直接返回
        if (cached != null && expiry != null && System.currentTimeMillis() < expiry) {
            return cached;
        }

        // 缓存失效，重新获取
        String ticket = iLinkApiClient.getConfig(botToken, baseUrl, ilinkUserId, contextToken);
        if (ticket != null) {
            typingTicketCache.put(cacheKey, ticket);
            typingTicketExpiry.put(cacheKey, System.currentTimeMillis() + TYPING_TICKET_TTL_MS);
            log.info("刷新 typing_ticket, cacheKey={}", cacheKey);
        }
        return ticket;
    }

    /**
     * 发送回复消息（封装 iLink 调用）
     */
    private void sendReply(WechatBot bot, String toUserId, String contextToken, String replyText) {
        try {
            String botToken = CryptoUtil.decrypt(bot.getBotToken());
            if (botToken == null) {
                log.warn("发送回复失败: Bot Token解密失败, botId={}", bot.getBotId());
                return;
            }

            boolean sent = iLinkApiClient.sendMessage(
                    botToken,
                    bot.getBaseUrl(),
                    toUserId,
                    replyText,
                    contextToken
            );

            if (sent) {
                log.info("回复成功, botId={}, toUserId={}, replyPreview={}",
                        bot.getBotId(), toUserId,
                        replyText.length() > 50 ? replyText.substring(0, 50) + "..." : replyText);
            } else {
                log.warn("回复失败, botId={}, toUserId={}", bot.getBotId(), toUserId);
            }
        } catch (Exception e) {
            log.error("发送回复异常, botId={}", bot.getBotId(), e);
        }
    }

    /**
     * 从消息 JSON 中提取文本内容
     */
    private String extractText(JSONObject msg) {
        try {
            JSONArray itemList = msg.getJSONArray("item_list");
            if (itemList != null && !itemList.isEmpty()) {
                JSONObject firstItem = itemList.getJSONObject(0);
                if (firstItem != null) {
                    JSONObject textItem = firstItem.getJSONObject("text_item");
                    if (textItem != null) {
                        String text = textItem.getStr("text");
                        if (text != null && !text.isEmpty()) {
                            return text;
                        }
                    }
                    String content = firstItem.getStr("content");
                    if (content != null && !content.isEmpty()) {
                        return content;
                    }
                }
            }
            String topContent = msg.getStr("content");
            if (topContent != null && !topContent.isEmpty()) {
                return topContent;
            }
        } catch (Exception e) {
            log.debug("提取消息文本异常, msg={}", msg, e);
        }

        log.warn("未能提取消息文本，完整消息JSON: {}", msg);
        return "";
    }

    /**
     * 检测消息是否疑似攻击/刷 token
     */
    private boolean isAbusive(String textContent) {
        if (textContent == null || textContent.isEmpty()) {
            return false;
        }
        return textContent.length() > 200;
    }

    private void persistUpdatesBuf(Long botId, String buf) {
        try {
            botMapper.updateGetUpdatesBuf(botId, buf);
        } catch (Exception e) {
            log.error("持久化 get_updates_buf 失败, botId={}", botId, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 昵称采集状态
     *
     * @param originalQuestion 用户最初的问题（采集完成后继续处理）
     * @param attemptCount    已尝试次数
     */
    private record NicknameCollectState(String originalQuestion, int attemptCount) {
    }
}

package com.renhe.di.ai.service;

import com.renhe.di.ai.routing.ModelRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 图片理解服务（多模态）
 * <p>
 * 通过 ModelRouter 获取 Kimi 视觉模型 ChatClient（Anthropic 协议），
 * 支持直接传图片 URL 或压缩后的 base64 数据给模型进行分析。
 * <p>
 * 优先使用图片 URL（不占用上下文 token），URL 方式失败时降级为压缩后的 base64。
 */
@Slf4j
@Service
public class ImageUnderstandingService {

    @Resource
    private ModelRouter modelRouter;

    // 图片压缩参数
    private static final int MAX_WIDTH = 1024;
    private static final int MAX_HEIGHT = 1024;
    private static final float JPEG_QUALITY = 0.7f;
    private static final int MAX_BASE64_SIZE_KB = 500;

    /**
     * 分析图片内容（优先使用 URL，不占用 token）
     *
     * @param imageUrl   图片 URL（微信图片外链）
     * @param userHint   用户的附加提示（如"请描述这张图片"）
     * @return 图片内容的文字描述
     */
    public String analyzeImageUrl(String imageUrl, String userHint) {
        try {
            ChatClient chatClient = modelRouter.routeForVision();
            String hint = (userHint != null && !userHint.isEmpty()) ? userHint : "请详细描述这张图片的内容。";

            log.info("调用图片理解服务(URL): url={}, hint={}", imageUrl, hint);

            // Anthropic Claude API 支持在文本消息中嵌入图片 URL
            String promptWithImage = hint + "\n\n![图片](" + imageUrl + ")";

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                log.debug("开始调用 Kimi 视觉模型(URL)...");
                String result = chatClient.prompt()
                        .user(promptWithImage)
                        .call()
                        .content();
                log.debug("Kimi 视觉模型调用完成(URL)");
                return result;
            });

            String result = future.get(90, TimeUnit.SECONDS);

            if (result == null || result.trim().isEmpty()) {
                log.warn("图片理解结果为空(URL)");
                return null;
            }

            log.info("图片理解完成(URL): resultLength={}, preview={}",
                    result.length(),
                    result.length() > 80 ? result.substring(0, 80) + "..." : result);

            return result;

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("图片理解服务调用超时（90秒）");
            return "图片分析超时，请稍后再试。";
        } catch (Exception e) {
            log.error("图片理解服务调用失败(URL): {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 分析图片内容（直接传入图片字节）
     * <p>
     * 压缩 -> 转 base64，控制最终大小不超过 500KB
     *
     * @param imageData  图片字节数据（已由调用方下载）
     * @param userHint   用户的附加提示
     * @return 图片内容的文字描述
     */
    public String analyzeImageBytes(byte[] imageData, String userHint) {
        try {
            if (imageData == null || imageData.length == 0) {
                log.warn("图片数据为空");
                return null;
            }

            log.info("压缩图片: originalSize={}KB", imageData.length / 1024);

            // 压缩图片
            byte[] compressedData = compressImage(imageData);
            log.info("图片压缩完成: compressedSize={}KB", compressedData.length / 1024);

            // 转为 base64
            String base64Image = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(compressedData);

            // 调用 base64 分析
            return analyzeImageBase64(base64Image, userHint);

        } catch (Exception e) {
            log.error("图片压缩分析失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 分析图片内容（base64 方式）
     */
    public String analyzeImageBase64(String imageBase64, String userHint) {
        try {
            ChatClient chatClient = modelRouter.routeForVision();
            String hint = (userHint != null && !userHint.isEmpty()) ? userHint : "请详细描述这张图片的内容。";

            log.info("调用图片理解服务(base64): imageSize={}KB, hint={}", imageBase64.length() / 1024, hint);

            String promptWithImage = hint + "\n\n![图片](" + imageBase64 + ")";

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                log.debug("开始调用 Kimi 视觉模型(base64)...");
                String result = chatClient.prompt()
                        .user(promptWithImage)
                        .call()
                        .content();
                log.debug("Kimi 视觉模型调用完成(base64)");
                return result;
            });

            String result = future.get(90, TimeUnit.SECONDS);

            if (result == null || result.trim().isEmpty()) {
                log.warn("图片理解结果为空(base64)");
                return null;
            }

            log.info("图片理解完成(base64): resultLength={}, preview={}",
                    result.length(),
                    result.length() > 80 ? result.substring(0, 80) + "..." : result);

            return result;

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("图片理解服务调用超时（90秒）");
            return "图片分析超时，请稍后再试。";
        } catch (Exception e) {
            log.error("图片理解服务调用失败(base64): {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 压缩图片：限制尺寸 + JPEG 质量压缩
     */
    private byte[] compressImage(byte[] imageData) throws IOException {
        log.debug("开始压缩图片: originalSize={}KB, 前16字节={}",
                imageData.length / 1024,
                bytesToHex(imageData, 16));

        // 尝试检测图片格式
        String format = detectImageFormat(imageData);
        log.debug("检测到图片格式: {}", format);

        // 读取原始图片
        BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
        if (originalImage == null) {
            // 尝试用 detected format 再次读取
            if (!"unknown".equals(format)) {
                originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
            }
            if (originalImage == null) {
                throw new IOException("无法读取图片数据，格式: " + format + ", 大小: " + imageData.length + " 字节");
            }
        }

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // 计算缩放比例
        double scale = 1.0;
        if (originalWidth > MAX_WIDTH || originalHeight > MAX_HEIGHT) {
            double scaleX = (double) MAX_WIDTH / originalWidth;
            double scaleY = (double) MAX_HEIGHT / originalHeight;
            scale = Math.min(scaleX, scaleY);
        }

        int newWidth = (int) (originalWidth * scale);
        int newHeight = (int) (originalHeight * scale);

        // 缩放图片
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        g.dispose();

        // JPEG 压缩
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // 回退：直接写入
            ImageIO.write(resizedImage, "jpeg", outputStream);
            return outputStream.toByteArray();
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(resizedImage, null, null), param);
        } finally {
            writer.dispose();
        }

        byte[] result = outputStream.toByteArray();

        // 如果仍然超过限制，进一步降低质量
        if (result.length > MAX_BASE64_SIZE_KB * 1024) {
            log.warn("压缩后仍超过{}KB，进一步降低质量", MAX_BASE64_SIZE_KB);
            return compressWithLowerQuality(resizedImage, 0.5f);
        }

        return result;
    }

    /**
     * 使用更低质量压缩
     */
    private byte[] compressWithLowerQuality(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpeg", outputStream);
            return outputStream.toByteArray();
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        return outputStream.toByteArray();
    }

    /**
     * 检测图片格式（通过文件头魔数）
     */
    private String detectImageFormat(byte[] data) {
        if (data.length < 8) return "unknown";
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        // PNG: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && (data[1] & 0xFF) == 0x50
                && (data[2] & 0xFF) == 0x4E && (data[3] & 0xFF) == 0x47) {
            return "png";
        }
        // GIF: GIF87a or GIF89a
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        // WEBP: RIFF....WEBP
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data.length > 11 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        // BMP: BM
        if (data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        return "unknown";
    }

    /**
     * 字节数组转十六进制（用于调试）
     */
    private String bytesToHex(byte[] data, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(data.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        if (data.length > maxLen) sb.append("...");
        return sb.toString().trim();
    }

    /**
     * 检查图片理解服务是否可用
     */
    public boolean isAvailable() {
        try {
            ChatClient client = modelRouter.routeForVision();
            return client != null;
        } catch (Exception e) {
            return false;
        }
    }
}

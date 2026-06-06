package com.renhe.di.ai.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OCR 服务（调用第三方 OCR API 解析文件内容）
 * <p>
 * 支持 PDF、图片等文件的文本提取，返回 markdown 格式结果。
 */
@Slf4j
@Service
public class OcrService {

    @Value("${ocr.base-url:https://ocr.rhzy.ai}")
    private String baseUrl;

    @Value("${ocr.timeout-seconds:600}")
    private int timeoutSeconds;

    @Value("${ocr.verify-ssl:true}")
    private boolean verifySsl;

    @Value("${ocr.backend:hybrid-auto-engine}")
    private String backend;

    @Value("${ocr.lang-list:ch}")
    private String langList;

    @Value("${ocr.table-enable-pdf:true}")
    private boolean tableEnablePdf;

    @Value("${ocr.table-enable-image:false}")
    private boolean tableEnableImage;

    @Value("${ocr.auto-rotate-pdf:false}")
    private boolean autoRotatePdf;

    @Value("${ocr.auto-rotate-image:true}")
    private boolean autoRotateImage;

    @Value("${ocr.api-key:}")
    private String apiKey;

    /**
     * 解析文件内容（同步调用，带超时保护）
     *
     * @param file 待解析的文件
     * @return markdown 格式的文本内容，失败返回 null
     */
    public String parseFile(File file) {
        try {
            log.info("调用OCR服务: file={}, size={}KB", file.getName(), file.length() / 1024);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> doParseFile(file));

            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);

            if (result == null || result.trim().isEmpty()) {
                log.warn("OCR结果为空: file={}", file.getName());
                return null;
            }

            log.info("OCR完成: file={}, resultLength={}", file.getName(), result.length());
            return result;

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("OCR调用超时({}秒): file={}", timeoutSeconds, file.getName());
            return null;
        } catch (Exception e) {
            log.error("OCR调用失败: file={}, error={}", file.getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 实际调用 OCR API
     */
    private String doParseFile(File file) {
        String url = baseUrl + "/api/parse/sync";

        HttpRequest request = HttpRequest.post(url)
                .timeout(timeoutSeconds * 1000)
                .header("Authorization", apiKey)
                .form("backend", backend)
                .form("lang_list", langList)
                .form("parse_method", "auto")
                .form("formula_enable", "true")
                .form("table_enable", "true")
                .form("auto_rotate", String.valueOf(isImage(file) ? autoRotateImage : autoRotatePdf))
                .form("file", file);

        if (!verifySsl) {
            request = request.setSSLProtocol("TLS");
        }

        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                log.warn("OCR API返回非200: status={}, body={}", response.getStatus(), response.body());
                return null;
            }

            String body = response.body();
            JSONObject json = new JSONObject(body);

            if (json.isEmpty()) {
                log.warn("OCR返回非JSON: {}", body);
                return null;
            }

            // 提取 markdown 内容
            String markdown = json.getStr("markdown");
            if (markdown != null && !markdown.isEmpty()) {
                return markdown;
            }

            // 备选：从 content_list 提取文本
            JSONArray contentList = json.getJSONArray("content_list");
            if (contentList != null && !contentList.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < contentList.size(); i++) {
                    JSONObject item = contentList.getJSONObject(i);
                    if (item != null) {
                        String text = item.getStr("text");
                        if (text != null && !text.isEmpty()) {
                            sb.append(text).append("\n\n");
                        }
                    }
                }
                return sb.toString().trim();
            }

            log.warn("OCR结果无内容: {}", body);
            return null;

        } catch (Exception e) {
            log.error("OCR API调用异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断是否为图片文件
     */
    private boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".webp") || name.endsWith(".bmp");
    }
}

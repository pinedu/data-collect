package com.renhe.di.collect.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单机浏览器指纹生成器
 *
 * <p>为每个账号在 JVM 生命周期内绑定一套固定的浏览器指纹（UA + Sec-Ch-Ua + Sec-Fetch-* 等），
 * 让第三方服务端认为请求来自真实浏览器，降低被识别为机器人的概率。
 *
 * <p>同一账号始终使用同一套指纹，避免 UA 频繁跳变触发风控。
 */
@Slf4j
@Component
public class BrowserFingerprint {

    // =========================================================
    // 指纹档案库（版本号必须严格对应）
    // =========================================================

    private static final List<Fingerprint> FINGERPRINTS = List.of(

            // Chrome 131 — Windows 11
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                    "\"Windows\"",
                    false),

            // Chrome 125 — Windows 10
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not_A Brand\";v=\"24\"",
                    "\"Windows\"",
                    false),

            // Chrome 126 — Windows 11
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                    "\"Google Chrome\";v=\"126\", \"Chromium\";v=\"126\", \"Not/A)Brand\";v=\"8\"",
                    "\"Windows\"",
                    false),

            // Edge 131 — Windows 11
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
                    "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                    "\"Windows\"",
                    false),

            // Edge 125 — Windows 11
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
                    "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not_A Brand\";v=\"24\"",
                    "\"Windows\"",
                    false),

            // Firefox 134 — Windows 10（Firefox 不发 Sec-Ch-Ua，用 null 表示）
            new Fingerprint(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
                    null, null,
                    true)
    );

    // Accept-Language 变体池
    private static final List<String> ACCEPT_LANGUAGES = List.of(
            "zh-CN,zh;q=0.9,en;q=0.8",
            "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            "zh-CN,zh;q=0.9",
            "zh-CN,zh;q=0.95,en;q=0.85"
    );

    /** 按账号缓存指纹，JVM 生命周期内不变 */
    private final ConcurrentHashMap<String, Fingerprint> accountFingerprints = new ConcurrentHashMap<>();

    /**
     * 获取指定账号的浏览器指纹请求头集合。
     * 同一账号始终返回同一套指纹，不同账号随机分配。
     *
     * @param account 账号标识
     * @return 可直接 putAll 合入请求头的 Map
     */
    public Map<String, String> getHeaders(String account) {
        Fingerprint fp = accountFingerprints.computeIfAbsent(account, k -> {
            Fingerprint picked = FINGERPRINTS.get(ThreadLocalRandom.current().nextInt(FINGERPRINTS.size()));
            log.info("账号[{}]绑定浏览器指纹: {}", account, truncate(picked.userAgent, 60));
            return picked;
        });

        String acceptLang = ACCEPT_LANGUAGES.get(
                ThreadLocalRandom.current().nextInt(ACCEPT_LANGUAGES.size()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", fp.userAgent);

        // Chromium 系浏览器才发 Sec-Ch-* 头，Firefox 不发
        if (!fp.isFirefox) {
            headers.put("Sec-Ch-Ua", fp.secChUa);
            headers.put("Sec-Ch-Ua-Platform", fp.secChUaPlatform);
            headers.put("Sec-Ch-Ua-Mobile", "?0");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Site", "same-origin");
        }

        headers.put("Accept-Language", acceptLang);
        headers.put("DNT", "1");
        headers.put("Connection", "keep-alive");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        return headers;
    }

    /**
     * 清除指定账号的指纹缓存（可用于强制重新分配，一般不调用）。
     */
    public void resetFingerprint(String account) {
        accountFingerprints.remove(account);
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // =========================================================
    // 指纹档案内部类
    // =========================================================

    private static class Fingerprint {
        final String userAgent;
        /** null 表示该浏览器不发此头（Firefox） */
        final String secChUa;
        final String secChUaPlatform;
        final boolean isFirefox;

        Fingerprint(String userAgent, String secChUa, String secChUaPlatform, boolean isFirefox) {
            this.userAgent = userAgent;
            this.secChUa = secChUa;
            this.secChUaPlatform = secChUaPlatform;
            this.isFirefox = isFirefox;
        }
    }
}

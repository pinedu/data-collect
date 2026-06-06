package com.renhe.di.core.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 加密工具类
 * 提供AES对称加密/解密能力，用于敏感数据（如密码）的存储
 */
@Slf4j
@Component
public class CryptoUtil {

    @Value("${crypto.aes-key:}")
    private String aesKeyConfig;

    private static AES aes;

    private static final String DEFAULT_KEY = "DataIntegration2025SecureKey!@#";

    @PostConstruct
    public void init() {
        String key = (aesKeyConfig != null && !aesKeyConfig.isEmpty()) ? aesKeyConfig : DEFAULT_KEY;
        // 使用MD5生成16字节密钥
        byte[] keyBytes = SecureUtil.md5(key).getBytes(StandardCharsets.UTF_8);
        aes = SecureUtil.aes(keyBytes);
        log.info("CryptoUtil初始化完成");
    }

    /**
     * AES加密
     *
     * @param plaintext 明文
     * @return Base64编码的密文
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] encrypted = aes.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * AES解密
     *
     * @param ciphertext Base64编码的密文
     * @return 明文
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            byte[] decrypted = aes.decrypt(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败，可能为明文数据", e);
            // 如果解密失败，可能是明文存储的旧数据，直接返回
            return ciphertext;
        }
    }

    /**
     * 判断是否为加密数据（简单启发式判断）
     */
    public static boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Base64编码的特征：长度为4的倍数，只包含特定字符
        return text.length() % 4 == 0 && text.matches("^[A-Za-z0-9+/=]+$");
    }
}

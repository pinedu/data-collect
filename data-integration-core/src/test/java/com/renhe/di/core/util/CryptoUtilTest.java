package com.renhe.di.core.util;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CryptoUtil单元测试
 */
class CryptoUtilTest {

    @Test
    void testEncryptDecrypt() {
        // 初始化AES（模拟Spring容器初始化）
        CryptoUtil util = new CryptoUtil();
        util.init();

        String plaintext = "testPassword123!@#";

        // 加密
        String encrypted = CryptoUtil.encrypt(plaintext);
        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted);
        assertTrue(CryptoUtil.isEncrypted(encrypted));

        // 解密
        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void testEncryptNullOrEmpty() {
        CryptoUtil util = new CryptoUtil();
        util.init();

        assertNull(CryptoUtil.encrypt(null));
        assertEquals("", CryptoUtil.encrypt(""));
    }

    @Test
    void testDecryptPlaintext() {
        CryptoUtil util = new CryptoUtil();
        util.init();

        // 解密明文应返回原文（兼容旧数据）
        String plaintext = "plainTextPassword";
        String result = CryptoUtil.decrypt(plaintext);
        assertEquals(plaintext, result);
    }

    @Test
    void testIsEncrypted() {
        assertFalse(CryptoUtil.isEncrypted(null));
        assertFalse(CryptoUtil.isEncrypted(""));
        assertFalse(CryptoUtil.isEncrypted("plaintext"));
        assertTrue(CryptoUtil.isEncrypted("dGVzdA==")); // Base64编码的"test"
    }
}

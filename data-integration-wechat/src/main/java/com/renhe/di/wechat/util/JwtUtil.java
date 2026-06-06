package com.renhe.di.wechat.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token 工具类
 * 用于微信扫码登录后的身份认证
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${wechat.jwt.secret:WechatBotAdmin2025SecretKey!@#$%^}")
    private String secretConfig;

    private SecretKey secretKey;

    /**
     * Token 有效期（毫秒），默认 24 小时
     */
    private static final long TOKEN_EXPIRE_MS = 24 * 60 * 60 * 1000L;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secretConfig.getBytes(StandardCharsets.UTF_8));
        log.info("JWT工具初始化完成");
    }

    /**
     * 生成 JWT Token
     *
     * @param userId 用户 ID
     * @param role   用户角色
     * @param openId 微信 OpenID
     * @return JWT Token
     */
    public String generateToken(Long userId, String role, String openId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("openId", openId);

        Date now = new Date();
        Date expireTime = new Date(now.getTime() + TOKEN_EXPIRE_MS);

        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expireTime)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 JWT Token
     *
     * @param token JWT Token
     * @return Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT Token验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Token 中获取用户 ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Long.class);
    }

    /**
     * 从 Token 中获取角色
     */
    public String getRole(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

    /**
     * 从 Token 中获取 OpenID
     */
    public String getOpenId(String token) {
        Claims claims = parseToken(token);
        return claims.get("openId", String.class);
    }
}

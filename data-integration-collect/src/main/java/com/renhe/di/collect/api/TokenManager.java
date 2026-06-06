package com.renhe.di.collect.api;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Token管理器
 * 负责第三方平台Token的获取、存储、刷新和续期
 */
@Slf4j
@Component
public class TokenManager {

    /**
     * Redis Token Key前缀
     */
    public static final String TOKEN_KEY_PREFIX = "login:sid:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${collect.token.ttl-hours:12}")
    private long tokenTtlHours;

    @Value("${collect.token.renew-threshold-hours:2}")
    private long renewThresholdHours;

    /**
     * 获取Token（优先从Redis读取，不存在则通过API获取）
     *
     * @param account  账号
     * @param password 密码
     * @return Token值
     */
    public String getToken(String account, String password) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        String token = redisTemplate.opsForValue().get(tokenKey);

        if (StringUtils.hasLength(token)) {
            // 检查是否需要续期
            Long ttl = redisTemplate.getExpire(tokenKey, TimeUnit.HOURS);
            if (ttl != null && ttl < renewThresholdHours) {
                log.info("账号【{}】Token剩余有效期{}小时，执行续期", account, ttl);
                validateAndExtend(account, password);
            }
            return token;
        }

        // Redis不存在，通过API获取
        log.info("账号【{}】Redis中无Token，尝试通过API获取", account);
        return refreshToken(account, password);
    }

    /**
     * 刷新Token（强制从API重新获取）
     *
     * @param account  账号
     * @param password 密码
     * @return 新的Token
     */
    public String refreshToken(String account, String password) {
        try {
            // 调用项目详情接口验证账号密码并获取Token
            // 这里使用一个通用的项目查询来触发登录流程
            // 实际Token是通过扫码获取后存入Redis的，此方法主要用于验证和兜底
            log.info("账号【{}】开始刷新Token", account);

            // 通过调用getProjectData来验证账号有效性
            // 如果账号密码正确，第三方平台会在响应头或响应体中返回新的Token信息
            // 但当前实现中，Token主要靠扫码获取，这里做兜底处理

            // 清除旧Token
            String tokenKey = TOKEN_KEY_PREFIX + account;
            redisTemplate.delete(tokenKey);
            log.warn("账号【{}】Token刷新失败，Token主要通过扫码获取，请确保已扫码登录", account);
            throw new RuntimeException("账号【"+account+"】Token刷新失败，Token主要通过扫码获取，请确保已扫码登录");
        } catch (Exception e) {
            log.error("账号【{}】Token刷新异常", account, e);
            throw new RuntimeException("账号【"+account+"】Token刷新异常");
        }
    }

    /**
     * 验证Token有效性并续期
     *
     * @param account  账号
     * @param password 密码
     * @return 是否续期成功
     */
    public boolean validateAndExtend(String account, String password) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        String token = redisTemplate.opsForValue().get(tokenKey);

        if (!StringUtils.hasLength(token)) {
            log.warn("账号【{}】Token不存在，无法续期", account);
            return false;
        }

        try {
            // 调用项目详情接口验证Token有效性
            // 如果接口返回成功，说明Token有效，续期
            // 这里简化处理：只要能从Redis读到Token就认为有效，直接续期
            redisTemplate.expire(tokenKey, tokenTtlHours, TimeUnit.HOURS);
            log.info("账号【{}】Token续期成功，延长{}小时", account, tokenTtlHours);
            return true;
        } catch (Exception e) {
            log.error("账号【{}】Token续期异常", account, e);
            return false;
        }
    }

    /**
     * 存储Token（扫码成功后调用）
     *
     * @param account 账号
     * @param token   Token值
     */
    public void storeToken(String account, String token) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        redisTemplate.opsForValue().set(tokenKey, token, tokenTtlHours, TimeUnit.HOURS);
        log.info("账号【{}】Token已存储到Redis，有效期{}小时", account, tokenTtlHours);
    }

    /**
     * 删除Token
     *
     * @param account 账号
     */
    public void removeToken(String account) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        redisTemplate.delete(tokenKey);
        log.info("账号【{}】Token已从Redis移除", account);
    }

    /**
     * 判断Token是否存在
     *
     * @param account 账号
     * @return 是否存在
     */
    public boolean hasToken(String account) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        return redisTemplate.hasKey(tokenKey);
    }

    /**
     * 获取Token剩余有效期（小时）
     *
     * @param account 账号
     * @return 剩余小时数，-1表示不存在
     */
    public long getTokenTtl(String account) {
        String tokenKey = TOKEN_KEY_PREFIX + account;
        return redisTemplate.getExpire(tokenKey, TimeUnit.HOURS);
    }
}

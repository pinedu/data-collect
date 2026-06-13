package com.renhe.di.collect.api;

import com.renhe.di.core.exception.AntiCrawlerException;

/**
 * 风控/反爬虫检测器 — 全局统一关键词 + cause链遍历
 * <p>
 * 统一管理所有风控关键词，避免在多处重复定义导致维护不一致。
 * 所有需要判断异常是否为风控触发的地方都应使用此类。
 */
public final class AntiCrawlerDetector {

    /** 风控/反爬虫关键词（匹配任一即视为风控拦截） */
    private static final String[] KEYWORDS = {
            "触发风控", "触发反爬虫", "风控", "反爬虫",
            "账号异常", "频繁", "限制", "系统异常", "请联系管理员"
    };

    private AntiCrawlerDetector() {}

    /**
     * 判断异常消息是否包含风控关键词（仅检查当前层）
     *
     * @param message 异常消息
     * @return true 表示匹配到风控关键词
     */
    public static boolean matchesMessage(String message) {
        if (message == null) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 遍历整个异常 cause 链，判断是否包含风控/反爬虫异常
     * <p>
     * 优先检查 instanceof AntiCrawlerException（最可靠），
     * 再逐层检查 message 中的关键词（兜底，因为异常可能被 CompletableFuture 等包装）。
     *
     * @param throwable 异常
     * @return true 表示该异常链中包含风控触发
     */
    public static boolean isAntiCrawler(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AntiCrawlerException) {
                return true;
            }
            if (matchesMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断异常是否为 Token 过期（401登录过期）
     * <p>
     * 遍历 cause 链，发现 TokenExpiredException 立即返回 true。
     *
     * @param throwable 异常
     * @return true 表示 Token 已过期
     */
    public static boolean isTokenExpired(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TokenExpiredException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

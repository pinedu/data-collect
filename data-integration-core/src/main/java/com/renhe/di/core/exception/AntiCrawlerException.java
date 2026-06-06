package com.renhe.di.core.exception;

/**
 * 反爬虫/风控异常
 * 触发后应立即终止当前项目的采集流程，避免继续请求加重风控。
 */
public class AntiCrawlerException extends RuntimeException {

    public AntiCrawlerException(String message) {
        super(message);
    }

    public AntiCrawlerException(String message, Throwable cause) {
        super(message, cause);
    }
}

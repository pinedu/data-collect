package com.renhe.di.collect.api;

/**
 * Token已过期异常
 * 当第三方API明确返回 code=401, msg=登录过期 时抛出
 * 区别于风控封号等临时性错误，此异常表示Token必须被移除
 */
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException(String message) {
        super(message);
    }
}

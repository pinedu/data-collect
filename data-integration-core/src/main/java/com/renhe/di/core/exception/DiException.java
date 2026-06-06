package com.renhe.di.core.exception;

import lombok.Getter;

/**
 * 数据整理服务统一异常
 */
@Getter
public class DiException extends RuntimeException {

    private final String code;
    private final String message;

    public DiException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public DiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }

    public static DiException of(String message) {
        return new DiException("DI_ERROR", message);
    }

    public static DiException of(String code, String message) {
        return new DiException(code, message);
    }
}

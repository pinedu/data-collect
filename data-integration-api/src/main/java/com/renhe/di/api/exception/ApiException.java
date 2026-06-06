package com.renhe.di.api.exception;

import lombok.Getter;

import java.io.Serializable;

/**
 * API层异常
 */
@Getter
public class ApiException extends RuntimeException implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ApiException(String message) {
        super(message);
        this.code = "API_ERROR";
    }

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}

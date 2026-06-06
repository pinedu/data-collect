package com.renhe.di.bootstrap.config;

import com.renhe.di.api.exception.ApiException;
import com.renhe.di.core.exception.DiException;
import com.renhe.di.core.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器
 * 统一捕获异常并返回规范化的 Result 响应
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * API层业务异常
     */
    @ExceptionHandler(ApiException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleApiException(ApiException e) {
        log.warn("API业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 数据整理服务异常
     */
    @ExceptionHandler(DiException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleDiException(DiException e) {
        log.warn("DI业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 请求参数缺失
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("请求参数缺失: {}", e.getMessage());
        return Result.fail(400, "缺少必要参数: " + e.getParameterName());
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {}", e.getMessage());
        return Result.fail(400, "参数类型错误: " + e.getName());
    }

    /**
     * 未知异常兜底
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统内部错误，请稍后重试");
    }
}

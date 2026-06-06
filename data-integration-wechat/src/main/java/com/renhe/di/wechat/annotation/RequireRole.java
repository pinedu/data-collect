package com.renhe.di.wechat.annotation;

import com.renhe.di.wechat.enums.UserRole;

import java.lang.annotation.*;

/**
 * 用户角色权限注解
 * 标记在 Controller 方法上，用于权限校验
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    /**
     * 允许访问的角色列表
     */
    UserRole[] value() default {UserRole.SUPER_ADMIN};
}

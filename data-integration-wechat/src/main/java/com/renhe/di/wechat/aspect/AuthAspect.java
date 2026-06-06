package com.renhe.di.wechat.aspect;

import com.renhe.di.core.model.Result;
import com.renhe.di.wechat.annotation.RequireRole;
import com.renhe.di.wechat.enums.UserRole;
import com.renhe.di.wechat.util.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限校验切面
 */
@Slf4j
@Aspect
@Component
public class AuthAspect {

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 拦截标注 @RequireRole 的方法进行权限校验
     */
    @Around("@annotation(com.renhe.di.wechat.annotation.RequireRole) || @within(com.renhe.di.wechat.annotation.RequireRole)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Result.fail(401, "未登录");
        }

        HttpServletRequest request = attributes.getRequest();
        String token = extractToken(request);
        if (token == null || !jwtUtil.validateToken(token)) {
            return Result.fail(401, "Token无效或已过期，请重新扫码登录");
        }

        // 获取当前用户角色
        String roleStr = jwtUtil.getRole(token);
        UserRole currentRole;
        try {
            currentRole = UserRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return Result.fail(403, "无效的用户角色");
        }

        // 获取方法上允许的角色
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireRole requireRole = signature.getMethod().getAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = joinPoint.getTarget().getClass().getAnnotation(RequireRole.class);
        }

        if (requireRole != null) {
            Set<UserRole> allowedRoles = Arrays.stream(requireRole.value()).collect(Collectors.toSet());
            if (!allowedRoles.contains(currentRole)) {
                log.warn("权限不足: 用户角色={}, 需要角色={}", currentRole, allowedRoles);
                return Result.fail(403, "权限不足");
            }
        }

        // 将用户信息存入 request attribute，供 Controller 使用
        request.setAttribute("currentUserId", jwtUtil.getUserId(token));
        request.setAttribute("currentRole", roleStr);
        request.setAttribute("currentOpenId", jwtUtil.getOpenId(token));

        return joinPoint.proceed();
    }

    /**
     * 从请求头中提取 Token
     */
    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // 也支持 query 参数传递
        return request.getParameter("token");
    }
}

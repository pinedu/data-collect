package com.renhe.di.wechat.controller;

import com.renhe.di.core.model.Result;
import com.renhe.di.store.entity.WechatUser;
import com.renhe.di.wechat.annotation.RequireRole;
import com.renhe.di.wechat.enums.UserRole;
import com.renhe.di.wechat.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理接口
 */
@Slf4j
@RestController
@RequestMapping("/wechat/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户列表（分页）
     */
    @GetMapping("/list")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN})
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            HttpServletRequest request) {
        String currentRole = (String) request.getAttribute("currentRole");
        Long currentUserId = (Long) request.getAttribute("currentUserId");

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WechatUser> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);

        // SUPER_ADMIN 可看全部，ADMIN 仅看自己
        if (!UserRole.SUPER_ADMIN.name().equals(currentRole)) {
            // ADMIN 只能看自己的信息
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WechatUser> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            wrapper.eq(WechatUser::getId, currentUserId);
            userService.page(page, wrapper);
        } else {
            userService.page(page);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("size", page.getSize());
        data.put("current", page.getCurrent());
        data.put("pages", page.getPages());

        return Result.success(data);
    }

    /**
     * 修改用户角色
     */
    @PutMapping("/{userId}/role")
    @RequireRole(UserRole.SUPER_ADMIN)
    public Result<Void> changeRole(@PathVariable Long userId, @RequestParam String role) {
        userService.changeUserRole(userId, role);
        return Result.success();
    }

    /**
     * 禁用/启用用户
     */
    @PutMapping("/{userId}/status")
    @RequireRole(UserRole.SUPER_ADMIN)
    public Result<Void> toggleStatus(@PathVariable Long userId, @RequestParam boolean enable) {
        userService.toggleUserStatus(userId, enable);
        return Result.success();
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.USER})
    public Result<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        String role = (String) request.getAttribute("currentRole");

        WechatUser user = userService.getById(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("role", role);
        data.put("nickname", user != null ? user.getNickname() : "");
        data.put("openId", user != null ? user.getOpenId() : "");

        return Result.success(data);
    }
}

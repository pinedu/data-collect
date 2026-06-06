package com.renhe.di.wechat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.WechatUser;
import com.renhe.di.store.mapper.WechatUserMapper;
import com.renhe.di.wechat.dto.BotCallbackDTO;
import com.renhe.di.wechat.enums.UserRole;
import com.renhe.di.wechat.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 微信用户服务实现
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<WechatUserMapper, WechatUser> implements UserService {

    @Override
    @Transactional
    public WechatUser createOrGetUser(BotCallbackDTO callbackDTO) {
        String openId = callbackDTO.getOpenId();

        // 查询是否已存在
        WechatUser existing = baseMapper.selectByOpenId(openId);
        if (existing != null) {
            // 更新昵称
            if (callbackDTO.getNickname() != null) {
                existing.setNickname(callbackDTO.getNickname());
                updateById(existing);
            }
            log.info("用户已存在, openId={}, role={}", openId, existing.getRole());
            return existing;
        }

        // 新用户
        WechatUser user = new WechatUser();
        user.setOpenId(openId);
        user.setNickname(callbackDTO.getNickname());

        // 第一个用户自动成为超级管理员
        long userCount = baseMapper.countActiveUsers();
        if (userCount == 0) {
            user.setRole(UserRole.SUPER_ADMIN.name());
            log.info("首个用户注册，自动成为超级管理员, openId={}", openId);
        } else {
            user.setRole(UserRole.USER.name());
            log.info("新用户注册, openId={}", openId);
        }

        user.setStatus("ACTIVE");
        baseMapper.insert(user);
        return user;
    }

    @Override
    public WechatUser getByOpenId(String openId) {
        return baseMapper.selectByOpenId(openId);
    }

    @Override
    @Transactional
    public void toggleUserStatus(Long userId, boolean enable) {
        WechatUser user = getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        user.setStatus(enable ? "ACTIVE" : "DISABLED");
        updateById(user);
        log.info("用户状态变更, userId={}, status={}", userId, user.getStatus());
    }

    @Override
    @Transactional
    public void changeUserRole(Long userId, String newRole) {
        WechatUser user = getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        user.setRole(newRole);
        updateById(user);
        log.info("用户角色变更, userId={}, newRole={}", userId, newRole);
    }
}

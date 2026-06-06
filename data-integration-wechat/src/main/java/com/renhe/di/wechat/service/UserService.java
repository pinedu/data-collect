package com.renhe.di.wechat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.WechatUser;
import com.renhe.di.wechat.dto.BotCallbackDTO;

/**
 * 微信用户服务
 */
public interface UserService extends IService<WechatUser> {

    /**
     * 处理扫码回调，创建或获取用户
     * 首个用户自动成为 SUPER_ADMIN
     *
     * @param callbackDTO 扫码回调数据
     * @return 用户实体
     */
    WechatUser createOrGetUser(BotCallbackDTO callbackDTO);

    /**
     * 根据 OpenID 查询用户
     */
    WechatUser getByOpenId(String openId);

    /**
     * 禁用/启用用户
     */
    void toggleUserStatus(Long userId, boolean enable);

    /**
     * 修改用户角色（仅 SUPER_ADMIN 可操作）
     */
    void changeUserRole(Long userId, String newRole);
}

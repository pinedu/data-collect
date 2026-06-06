package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 微信用户实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wechat_user")
public class WechatUser extends BaseEntity {

    private Long id;

    /**
     * 微信 OpenID（扫码登录标识）
     */
    private String openId;

    /**
     * 微信号
     */
    private String userName;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 角色：SUPER_ADMIN / ADMIN / USER
     */
    private String role;

    /**
     * 状态：ACTIVE / DISABLED
     */
    private String status;
}

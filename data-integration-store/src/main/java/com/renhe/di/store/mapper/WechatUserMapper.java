package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.WechatUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 微信用户 Mapper
 */
@Mapper
public interface WechatUserMapper extends BaseMapper<WechatUser> {

    /**
     * 根据 OpenID 查询用户
     */
    @Select("SELECT * FROM wechat_user WHERE open_id = #{openId} AND deleted = 0 LIMIT 1")
    WechatUser selectByOpenId(@Param("openId") String openId);

    /**
     * 统计用户总数（用于判断是否首个用户）
     */
    @Select("SELECT COUNT(*) FROM wechat_user WHERE deleted = 0")
    long countActiveUsers();

    /**
     * 更新用户姓名
     */
    @Update("UPDATE wechat_user SET user_name = #{userName} WHERE id = #{id} AND deleted = 0")
    int updateUserName(@Param("id") Long id, @Param("userName") String userName);

    /**
     * 统计在指定时间之前注册的用户数（用于计算用户序号 N）
     * 包含当前用户自身，即 N = 第几位注册用户
     */
    @Select("SELECT COUNT(*) FROM wechat_user WHERE created_at <= #{createdAt} AND deleted = 0")
    int countRegisteredBefore(@Param("createdAt") LocalDateTime createdAt);
}

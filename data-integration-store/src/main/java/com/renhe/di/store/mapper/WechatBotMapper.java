package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.WechatBot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 微信Bot Mapper
 */
@Mapper
public interface WechatBotMapper extends BaseMapper<WechatBot> {

    /**
     * 根据 Bot ID 查询
     */
    @Select("SELECT * FROM wechat_bot WHERE bot_id = #{botId} AND deleted = 0 LIMIT 1")
    WechatBot selectByBotId(@Param("botId") String botId);

    /**
     * 查询所有已审核通过的 Bot
     */
    @Select("SELECT * FROM wechat_bot WHERE status = 'APPROVED' AND deleted = 0")
    List<WechatBot> selectApprovedBots();

    /**
     * 根据用户 ID 查询 Bot 列表
     */
    @Select("SELECT * FROM wechat_bot WHERE user_id = #{userId} AND deleted = 0")
    List<WechatBot> selectByUserId(@Param("userId") Long userId);

    /**
     * 根据 ilink_user_id 查询 Bot（同一微信用户多次扫码复用）
     */
    @Select("SELECT * FROM wechat_bot WHERE ilink_user_id = #{ilinkUserId} AND deleted = 0 LIMIT 1")
    WechatBot selectByIlinkUserId(@Param("ilinkUserId") String ilinkUserId);

    /**
     * 更新 context_token
     */
    @org.apache.ibatis.annotations.Update("UPDATE wechat_bot SET context_token = #{contextToken}, updated_at = NOW() WHERE id = #{id}")
    int updateContextToken(@Param("id") Long id, @Param("contextToken") String contextToken);

    /**
     * 更新 get_updates_buf 游标
     */
    @org.apache.ibatis.annotations.Update("UPDATE wechat_bot SET get_updates_buf = #{buf}, updated_at = NOW() WHERE id = #{id}")
    int updateGetUpdatesBuf(@Param("id") Long id, @Param("buf") String buf);
}

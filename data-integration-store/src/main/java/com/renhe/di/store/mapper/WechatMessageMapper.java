package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.WechatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 微信消息记录 Mapper
 */
@Mapper
public interface WechatMessageMapper extends BaseMapper<WechatMessage> {

    /**
     * 查询待发送的定时消息
     */
    @Select("SELECT * FROM wechat_message WHERE status = 'PENDING' AND scheduled_at <= NOW() AND deleted = 0")
    List<WechatMessage> selectPendingScheduledMessages();

    /**
     * 根据 Bot ID 查询消息列表
     */
    @Select("SELECT * FROM wechat_message WHERE bot_id = #{botId} AND deleted = 0 ORDER BY created_at DESC")
    List<WechatMessage> selectByBotId(@Param("botId") Long botId);
}

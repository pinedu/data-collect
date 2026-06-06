ALTER TABLE wechat_bot
    MODIFY COLUMN ilink_user_id VARCHAR(128) DEFAULT NULL COMMENT 'iLink 用户ID（扫码者，格式 xxx@im.wechat）' AFTER context_token;

ALTER TABLE wechat_bot
    MODIFY COLUMN get_updates_buf TEXT DEFAULT NULL COMMENT 'getupdates 长轮询游标' AFTER ilink_user_id;
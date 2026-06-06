-- ============================================
-- V1__wechat_bot_tables.sql
-- 微信Bot多用户管理系统核心表
-- ============================================

-- 微信用户表
CREATE TABLE IF NOT EXISTS wechat_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    open_id     VARCHAR(128) NOT NULL COMMENT '微信OpenID（扫码登录标识）',
    nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：SUPER_ADMIN/ADMIN/USER',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_open_id (open_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信用户表';

-- 微信Bot表
CREATE TABLE IF NOT EXISTS wechat_bot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    bot_id          VARCHAR(128) NOT NULL COMMENT 'iLink Bot 唯一标识',
    bot_name        VARCHAR(128) DEFAULT NULL COMMENT 'Bot名称/备注',
    user_id         BIGINT       DEFAULT NULL COMMENT '绑定用户ID',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    bot_token       VARCHAR(512) DEFAULT NULL COMMENT 'iLink Bot Token（加密存储）',
    context_token   VARCHAR(512) DEFAULT NULL COMMENT '推送上下文Token',
    base_url        VARCHAR(256) DEFAULT NULL COMMENT 'iLink API地址',
    token_expire_at DATETIME     DEFAULT NULL COMMENT 'Token过期时间',
    approved_by     BIGINT       DEFAULT NULL COMMENT '审核人ID',
    approved_at     DATETIME     DEFAULT NULL COMMENT '审核时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_bot_id (bot_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信Bot表';

-- 消息记录表
CREATE TABLE IF NOT EXISTS wechat_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    bot_id          BIGINT       NOT NULL COMMENT '关联Bot ID',
    target_user_id  VARCHAR(128) NOT NULL COMMENT '目标微信用户ID',
    message_type    VARCHAR(20)  NOT NULL DEFAULT 'TEXT' COMMENT '消息类型：TEXT/IMAGE/FILE',
    content         TEXT         NOT NULL COMMENT '消息内容',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/SENT/FAILED',
    error_msg       VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    scheduled_at    DATETIME     DEFAULT NULL COMMENT '计划发送时间',
    sent_at         DATETIME     DEFAULT NULL COMMENT '实际发送时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_bot_id (bot_id),
    INDEX idx_status (status),
    INDEX idx_scheduled_at (scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息记录表';

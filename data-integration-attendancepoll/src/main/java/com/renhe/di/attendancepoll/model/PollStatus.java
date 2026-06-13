package com.renhe.di.attendancepoll.model;

/**
 * 考勤持续采集状态机
 *
 * <pre>
 * IDLE ──(Scheduler调度)──▶ PROBING ──(探针成功)──▶ COLLECTING
 *   ▲                         │                        │
 *   │                    (风控触发)               (风控触发)
 *   │                         │                        │
 *   │                         ▼                        ▼
 *   │                   RATE_LIMITED ──(冷却到期)──▶ IDLE
 *   │
 *   ├──(Token失效)──▶ TOKEN_EXPIRED ──(Watcher检测新Token)──▶ IDLE
 *   │
 *   └──(一轮完成)──▶ COMPLETED ──(等待间隔)──▶ IDLE
 * </pre>
 */
public enum PollStatus {

    /** 等待调度（初始状态 / 冷却结束后回到此状态） */
    IDLE,

    /** 全局探针中（Phase A：获取 globalTotal + earliestClockingTime） */
    PROBING,

    /** 逐月采集中（Phase C：按月份串行流式采集） */
    COLLECTING,

    /** 风控冷却等待（冷却到期后自动恢复为 IDLE） */
    RATE_LIMITED,

    /** Token 失效暂停（TokenVersionWatcher 检测到新 Token 后恢复为 IDLE） */
    TOKEN_EXPIRED,

    /** 本轮采集完成（等待 pollIntervalSeconds 后回到 IDLE） */
    COMPLETED
}

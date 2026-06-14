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
 *   │                   RATE_LIMITED ──(冷却到期)──▶ RECOVERING ──(新任务开始)──▶ PROBING
 *   │
 *   ├──(Token失效)──▶ TOKEN_EXPIRED ──(Watcher检测新Token)──▶ IDLE
 *   │
 *   └──(一轮完成)──▶ COMPLETED ──(等待间隔)──▶ IDLE
 * </pre>
 */
public enum PollStatus {

    /** 等待调度（初始状态 / 本轮完成） */
    IDLE,

    /** 全局探针中（Phase A：获取 globalTotal + earliestClockingTime） */
    PROBING,

    /** 逐页采集中（Phase B：平铺翻页流式采集） */
    COLLECTING,

    /** 风控冷却等待（冷却到期后先变为 RECOVERING，再提交新任务） */
    RATE_LIMITED,

    /** 恢复中（已提交新任务但尚未开始执行，防止 waitForCompletion 误返回） */
    RECOVERING,

    /** Token 失效暂停（TokenVersionWatcher 检测到新 Token 后恢复为 IDLE） */
    TOKEN_EXPIRED,

    /** 本轮采集完成 */
    COMPLETED
}

package com.renhe.di.ai.routing;

/**
 * 任务类型枚举
 * <p>
 * 用于模型路由决策，根据用户问题内容分类到不同任务类型，
 * 再映射到最适合处理的 AI 模型。
 */
public enum TaskType {

    /**
     * SQL 查询类：涉及数据库查询、统计、列表
     * 路由到：DeepSeek（快速、低成本）
     */
    SQL_QUERY,

    /**
     * 图片理解类：用户发送图片要求分析
     * 路由到：Kimi（多模态能力强）
     */
    VISION,

    /**
     * 复杂推理类：分析、总结、对比、评估
     * 路由到：Kimi（推理能力强）
     */
    COMPLEX_REASONING,

    /**
     * 工具调用类：考勤、人员、项目、班组等业务查询
     * 路由到：DeepSeek（Function Calling 稳定）
     */
    TOOL_CALLING,

    /**
     * 闲聊/其他：问候、简单问答
     * 路由到：DeepSeek（快速响应）
     */
    CHAT
}

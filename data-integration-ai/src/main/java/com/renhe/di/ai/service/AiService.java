package com.renhe.di.ai.service;

import com.renhe.di.ai.routing.ModelRouter;
import com.renhe.di.ai.service.InteractService;
import com.renhe.di.ai.tool.AttendanceTool;
import com.renhe.di.ai.tool.PersonTool;
import com.renhe.di.ai.tool.ProjectTool;
import com.renhe.di.ai.tool.RemoteAgentTool;
import com.renhe.di.ai.tool.SqlQueryTool;
import com.renhe.di.ai.tool.TeamTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * AI 核心服务
 * <p>
 * 接收用户自然语言问题，通过 ModelRouter 动态选择最优 AI 模型，
 * 调用 Spring AI ChatClient + Function Calling，
 * LLM 自动选择对应的 @Tool 方法查询数据库，返回脱敏后的摘要回复。
 * <p>
 * 通过 MessageChatMemoryAdvisor 实现多用户会话记忆，
 * 每个微信用户（conversationId）拥有独立的对话上下文。
 * <p>
 * 模型路由策略：
 * - SQL 查询 / 工具调用 / 闲聊 → DeepSeek（快速、低成本）
 * - 图片理解 / 复杂分析 → Kimi（多模态、强推理）
 */
@Slf4j
@Service
public class AiService {

    @Resource
    private ModelRouter modelRouter;

    @Resource
    private AttendanceTool attendanceTool;

    @Resource
    private ProjectTool projectTool;

    @Resource
    private PersonTool personTool;

    @Resource
    private TeamTool teamTool;

    @Resource
    private SqlQueryTool sqlQueryTool;

    @Resource
    private InteractService interactService;

    @Resource
    private RemoteAgentTool remoteAgentTool;

    // 欢迎语兜底（Markdown 结构化）
    private static final String FALLBACK_REPLY =
            """
            ## 你好，我是筑采助手
            > 专注建筑行业数据查询与分析
            | 我能做什么 | 示例问题 |
            |---|---|
            | **项目查询** | "查一下亚洲开发银行项目的数据" |
            | **考勤统计** | "最近一周的考勤情况" |
            | **班组建制** | "列出所有在册班组" |
            | **工资汇总** | "本月各班组工资总额" |
            | **AI 政务助手** | "建筑工程合同备案流程" |
            ---
            > 直接告诉我你想了解什么
            """;

    // AI 不可用降级
    private static final String AI_UNAVAILABLE =
            """
            > AI 服务暂时不可用，请稍后再试。
            > 如持续无法使用，可联系管理员。
            """;

    /**
     * 处理用户自然语言问题（带会话记忆 + 动态模型路由）
     *
     * @param conversationId 会话 ID（建议格式: "botId:fromUserId"）
     * @param userQuestion   用户的自然语言消息
     * @return AI 回复文本
     */
    public String handle(String conversationId, String userQuestion) {
        if (userQuestion == null || userQuestion.trim().isEmpty()) {
            return FALLBACK_REPLY;
        }

        try {
            // 动态路由：根据问题类型选择最优模型
            ChatClient chatClient = modelRouter.route(userQuestion);

            String reply = chatClient.prompt()
                    .user(userQuestion)
                    .advisors(a -> a
                            .param(CONVERSATION_ID, conversationId))
                    .tools(attendanceTool, projectTool, personTool, teamTool, sqlQueryTool, remoteAgentTool, interactService)
                    .call()
                    .content();

            if (reply == null || reply.trim().isEmpty()) {
                log.warn("AI 返回空回复, conversationId={}, question={}", conversationId, userQuestion);
                return FALLBACK_REPLY;
            }

            log.info("AI 回复: conversationId={}, question={}, replyPreview={}",
                    conversationId, userQuestion,
                    reply.length() > 80 ? reply.substring(0, 80) + "..." : reply);

            return reply;

        } catch (Exception e) {
            log.error("AI 服务调用异常, conversationId={}, question={}", conversationId, userQuestion, e);
            return AI_UNAVAILABLE;
        }
    }

    /**
     * 从用户自然语言回复中提取昵称（无会话记忆，独立调用）
     * <p>
     * 用户回复"叫我张三"、"我叫李四"等不确定话术时，
     * 通过 AI 理解语义，提取出真正的名字部分。
     *
     * @param userMessage 用户回复的原始文本
     * @return 提取到的昵称，提取失败返回 null
     */
    public String extractNickname(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return null;
        }

        try {
            ChatClient chatClient = modelRouter.route(userMessage);

            String reply = chatClient.prompt()
                    .system("""
                            你是一个姓名提取工具。用户会告诉你如何称呼他/她，请从用户的消息中提取出他/她希望被称呼的名字。

                            规则：
                            1. 只输出名字本身，不要有任何其他文字、标点或解释
                            2. 如果用户说"叫我张三"，输出"张三"
                            3. 如果用户说"我叫李四"，输出"李四"
                            4. 如果用户说"你就叫我老王吧"，输出"老王"
                            5. 如果用户说"王五"，输出"王五"
                            6. 如果无法从消息中提取出名字，只输出一个减号：-
                            """)
                    .user(userMessage)
                    .call()
                    .content();

            if (reply == null || reply.trim().isEmpty() || "-".equals(reply.trim())) {
                return null;
            }

            String extracted = reply.trim();
            log.info("AI 昵称提取: input={}, output={}", userMessage, extracted);
            return extracted;
        } catch (Exception e) {
            log.warn("AI 昵称提取失败: {}", e.getMessage());
            return null;
        }
    }
}

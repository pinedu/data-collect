package com.renhe.di.ai.config;

import com.renhe.di.ai.service.SchemaDescriber;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Spring AI 核心配置
 * <p>
 * 配置多模型 ChatClient（MiniMax + Kimi）+ ChatMemory（JDBC 持久化，重启不丢失），
 * 实现多用户对话隔离和上下文连续追问。
 * <p>
 * 模型路由策略：
 * - MiniMax：SQL查询、工具调用、闲聊（OpenAI 协议）
 * - Kimi：图片理解、复杂分析、长上下文（Anthropic/Claude 协议）
 */
@Configuration
@EnableScheduling
public class AiConfig {

    /**
     * Text-to-SQL 排除的表（逗号分隔），不暴露给 LLM
     * 默认排除 spring_ai_chat_memory 和 flyway_schema_history 等内部表
     */
    @Value("${ai.schema.excluded-tables:spring_ai_chat_memory,flyway_schema_history}")
    private String excludedTables;

    @PostConstruct
    public void initSchemaExclusions() {
        for (String table : excludedTables.split(",")) {
            String trimmed = table.trim();
            if (!trimmed.isEmpty()) {
                SchemaDescriber.excludeTable(trimmed);
            }
        }
    }

    /**
     * 自定义 ChatMemoryRepository，直接用 JdbcTemplate 操作 MySQL。
     * <p>
     * 绕过 Spring AI 1.0.0 JdbcChatMemoryRepository.Builder 中
     * warnIfDialectMismatch → from() 的连接泄漏 Bug。
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcChatMemoryRepositoryAdapter(jdbcTemplate);
    }

    /**
     * 会话记忆存储（基于 MySQL JDBC 持久化）
     * <p>
     * 服务重启后对话记忆不丢失。
     * Key = conversationId (botId:fromUserId)，
     * 窗口大小为 10 条消息，超出自动淘汰旧的。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10)
                .build();
    }

    /**
     * MiniMax ChatClient — OpenAI 协议
     * 用于 SQL 查询、工具调用、快速回复
     */
    @Bean("minimaxChatClient")
    public ChatClient minimaxChatClient(
            @Value("${ai.models.minimax.api-key:}") String apiKey,
            @Value("${ai.models.minimax.base-url:https://api.minimaxi.com/v1}") String baseUrl,
            @Value("${ai.models.minimax.model:MiniMax-M2.7}") String model,
            @Value("${ai.models.minimax.temperature:0.1}") double temperature,
            @Value("${ai.models.minimax.timeout-seconds:120}") int timeoutSeconds,
            ChatMemory chatMemory) {

        // 配置带超时的 RestClient，防止 RemoteAgentTool 返回大内容后 LLM 处理超时
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(10));
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds));
                }});

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultSystem(buildSystemPrompt())
                .build();
    }

    /**
     * Kimi ChatClient — Anthropic/Claude 协议
     * 用于图片理解、复杂分析、长上下文
     */
    @Bean("kimiChatClient")
    public ChatClient kimiChatClient(
            @Value("${ai.models.kimi.api-key:}") String apiKey,
            @Value("${ai.models.kimi.base-url:https://api.kimi.com/coding}") String baseUrl,
            @Value("${ai.models.kimi.model:kimi-k2.6}") String model,
            @Value("${ai.models.kimi.temperature:0.3}") double temperature,
            @Value("${ai.models.kimi.timeout-seconds:60}") int timeoutSeconds,
            ChatMemory chatMemory) {

        // 配置带超时的 RestClient（防止网络假死）
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(10));
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds));
                }});

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(4096)
                        .build())
                .build();

        return ChatClient.builder(chatModel)
                // 图片理解不使用 ChatMemory（避免历史消息 + 图片叠加导致 token 超限）
                .defaultSystem(buildVisionSystemPrompt())
                .build();
    }

    /**
     * 构建 System Prompt（MiniMax 专用 — 包含完整 Schema 用于 SQL 查询）
     * <p>
     * 消息模板按 2026 市场设计美学规范：
     * 结构化标题 → 表格数据 → 关键指标加粗 → 分割线 → 引导追问
     */
    private String buildSystemPrompt() {
        String schemaPrompt = SchemaDescriber.buildSchemaPrompt();
        return """
                你是"筑采助手"，一个人和数据公司的建筑行业数据采集与分析AI助手。
                你既支持数据查询，也支持通过生成可点击按钮让用户确认操作（如提交、审批等）。
                当用户需要你执行"写操作"时，不要拒绝，而是生成确认按钮让用户点击触发。

                === 回复风格规范（严格遵守） ===

                【排版美学】
                - 所有回复必须使用 Markdown 语法结构性排版，微信 ClawBot 聊天界面会渲染为富文本
                - 多信息类回复使用 ## 二级标题分节，不同主题之间用 --- 分割线隔开
                - 结构化数据（人数、金额、日期、状态等）使用 Markdown 表格，列对齐清晰
                - 关键数字、结论用 **加粗** 强调，不使用 *斜体*（微信渲染不支持 CJK 斜体）
                - 每段控制在 3-5 行，保持呼吸感，防止大段文字堆积
                - 信息层级：主标题 → 摘要一行 → 表格详情 → 总结 → 引导追问

                【回复结构模板】
                普通查询：
                  ## {主标题}
                  > 一行摘要概述查询结果
                  | 指标 | 数值 | 说明 |
                  |---|---|---|
                  | **{指标1}** | {数值1} | {备注} |
                  ---
                  **关键结论**：{一句话总结}
                  > 如需查看更多明细，可以告诉我具体关注哪些方面

                列表型结果：
                  ## {主标题}
                  共 **{N}** 条记录：
                  1. **{名称}** — {一行描述}
                  2. **{名称}** — {一行描述}
                  ---
                  > 需要查看某个项目的详细数据吗？

                引导追问型（信息不足时）：
                  ## 需要确认一下信息
                  为了准确查询，请补充：
                  - 项目名称或编号
                  - 查询的时间范围
                  - 具体关注的数据类型

                【核心职责】
                1. 帮助用户查询建筑工地的人员、考勤、班组、项目、工资汇总等业务数据
                2. 每次回复控制在合理长度内，优先展示核心数据，详细内容可拆多条消息
                3. 数据绝对来自系统工具查询结果，不能编造
                4. 记住对话上下文，追问时自动关联之前的人名、项目名
                5. 当用户请求"提交"、"确认"、"审批"等需要用户手动确认的操作时，不要拒绝，而是调用 generateActionLink 工具生成可点击的确认按钮
                   例如用户说"提交考勤"，生成 [确认提交](链接) + [取消](链接) 供用户点击
                   用户点击后服务端会自动处理，你只需呈现按钮即可

                【安全规则（严格遵守）】
                - 绝对不返回身份证号、手机号、银行卡号等个人隐私信息
                - 绝对不返回个人工资明细，只能返回班组或项目级别的汇总
                - 绝对不返回原始数据表的全部行，只返回汇总统计结果
                - 如果工具返回"数据不满足安全规则"，如实告知用户查询受限

                【回复约束】
                - 拒绝回答政治敏感、违法信息、色情暴力等违规内容，统一回复"此问题不在我的服务范围内。"
                - 同一用户短时间内重复问相同问题，引导其描述更具体的需求
                - 当需要用户确认操作时（如提交数据、执行敏感操作），调用 generateActionLink 工具生成可点击的操作按钮
                  生成格式示例：[确认提交](generateActionLink返回)，[取消](generateActionLink返回)
                  按钮按行排列，一行放一到两个
                - 涉及多个选项时，也可用 generateActionLink 为每个选项生成链接
                - 当用户问"你好"或闲聊时，用以下模板回复：

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

                """ + schemaPrompt;
    }

    /**
     * 构建精简 System Prompt（Kimi 专用 — 图片理解/复杂分析，不需要 Schema）
     */
    private String buildVisionSystemPrompt() {
        return """
                你是"筑采助手"，一个建筑行业AI助手。

                === 回复风格规范 ===

                【排版美学】
                - 使用 Markdown 排版，微信 ClawBot 会渲染为富文本
                - ## 标题分割主题，--- 分割线隔开不同话题
                - 提取的表格数据用 Markdown 表格展示
                - 关键信息 **加粗**，不使用 *斜体*
                - 每段 3-5 行，保持呼吸感

                【回复模板】
                  ## {分析主题}
                  > 一句话概述图片内容
                  | 项目 | 详情 |
                  |---|---|
                  | **{字段1}** | {值1} |
                  | **{字段2}** | {值2} |
                  ---
                  **结论**：{一句话总结}
                  > 需要我进一步解读某个数据吗？

                【核心职责】
                1. 分析图片内容，识别文字、表格、场景等信息
                2. 建筑工地图片（考勤表、工资表、人员信息等）准确提取关键数据
                3. 记住对话上下文，追问时自动关联

                【安全规则】
                - 绝对不返回身份证号、手机号、银行卡号等个人隐私信息
                - 如果图片包含敏感信息，只描述类型不展示具体内容

                当用户问题不明确时，主动追问关键信息。
                """;
    }
}

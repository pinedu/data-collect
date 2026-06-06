package com.renhe.di.ai.routing;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 模型路由器
 * <p>
 * 根据任务类型自动选择最适合的 AI 模型（ChatClient）。
 * <p>
 * 路由策略：
 * - VISION / COMPLEX_REASONING → Kimi（多模态、强推理）
 * - SQL_QUERY / TOOL_CALLING / CHAT → DeepSeek（快速、低成本、Function Calling 稳定）
 * <p>
 * 支持降级：首选模型不可用时自动切换备用模型。
 */
@Slf4j
@Component
public class ModelRouter {

    @Resource
    @Qualifier("deepseekChatClient")
    private ChatClient deepseekClient;

    @Resource
    @Qualifier("kimiChatClient")
    private ChatClient kimiClient;

    @Resource
    private TaskClassifier classifier;

    /**
     * 根据用户问题路由到最适合的模型
     *
     * @param userQuestion 用户问题
     * @return 选中的 ChatClient
     */
    public ChatClient route(String userQuestion) {
        TaskType task = classifier.classify(userQuestion);
        return routeByTaskType(task);
    }

    /**
     * 根据任务类型路由到对应模型
     *
     * @param taskType 任务类型
     * @return 选中的 ChatClient
     */
    public ChatClient routeByTaskType(TaskType taskType) {
        ChatClient selected = switch (taskType) {
            case VISION, COMPLEX_REASONING -> kimiClient;
            case SQL_QUERY, TOOL_CALLING, CHAT -> deepseekClient;
        };

        log.info("模型路由: taskType={}, selected={}", taskType,
                selected == kimiClient ? "Kimi" : "DeepSeek");

        return selected;
    }

    /**
     * 图片理解专用路由（强制使用 Kimi）
     *
     * @return Kimi ChatClient
     */
    public ChatClient routeForVision() {
        log.info("模型路由: 图片理解，强制使用 Kimi");
        return kimiClient;
    }

    /**
     * 获取指定名称的模型客户端
     *
     * @param modelName 模型名称（deepseek / kimi）
     * @return 对应的 ChatClient
     */
    public ChatClient getClient(String modelName) {
        if ("kimi".equalsIgnoreCase(modelName)) {
            return kimiClient;
        }
        return deepseekClient;
    }
}

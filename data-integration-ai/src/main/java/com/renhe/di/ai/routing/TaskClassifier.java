package com.renhe.di.ai.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 任务分类器
 * <p>
 * 极简实现：只识别图片理解（必须路由到 Kimi 多模态模型），
 * 其他所有请求都交给 LLM 自主判断意图和工具选择。
 * <p>
 * 设计原则：意图识别完全由 AI 大模型自主分析，
 * 不通过预设规则限制 LLM 的工具调用决策。
 */
@Slf4j
@Component
public class TaskClassifier {

    // 仅保留图片理解关键词（VISION 必须路由到 Kimi）
    private static final List<String> VISION_KEYWORDS = Arrays.asList(
            "图片", "截图", "照片", "看看", "这张图", "这张", "发图", "上传图片",
            "image", "picture", "photo", "截图看看", "看一下这张图"
    );

    /**
     * 分类用户问题到任务类型
     * <p>
     * 仅识别 VISION（图片理解必须走 Kimi），其他全部交给 LLM 自主判断。
     *
     * @param userQuestion 用户的自然语言消息
     * @return 任务类型
     */
    public TaskType classify(String userQuestion) {
        if (userQuestion == null || userQuestion.trim().isEmpty()) {
            return TaskType.CHAT;
        }

        String lower = userQuestion.toLowerCase();

        // 仅保留 VISION 识别（多模态必须路由到 Kimi）
        if (containsAny(lower, VISION_KEYWORDS)) {
            log.debug("任务分类: VISION, question={}", userQuestion);
            return TaskType.VISION;
        }

        // 其他所有请求都走 TOOL_CALLING，让 LLM 自主决定调用哪些工具
        log.debug("任务分类: TOOL_CALLING(交由LLM自主判断), question={}", userQuestion);
        return TaskType.TOOL_CALLING;
    }

    /**
     * 检查文本是否包含任意关键词
     */
    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}

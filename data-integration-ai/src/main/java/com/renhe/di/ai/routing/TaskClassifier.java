package com.renhe.di.ai.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 任务分类器
 * <p>
 * 基于关键词规则引擎，将用户问题分类到不同的任务类型。
 * 轻量实现，不依赖 LLM 调用，避免递归消耗 Token。
 * <p>
 * 分类规则（按优先级从高到低）：
 * 1. VISION：含图片相关关键词
 * 2. COMPLEX_REASONING：含分析/总结/对比等复杂推理关键词
 * 3. SQL_QUERY：含查询/统计/列表等数据查询关键词
 * 4. TOOL_CALLING：含考勤/人员/项目等业务工具关键词
 * 5. CHAT：其他闲聊或简单问答
 */
@Slf4j
@Component
public class TaskClassifier {

    // 图片理解关键词
    private static final List<String> VISION_KEYWORDS = Arrays.asList(
            "图片", "截图", "照片", "看看", "这张图", "这张", "发图", "上传图片",
            "image", "picture", "photo", "截图看看", "看一下这张图"
    );

    // 复杂推理关键词
    private static final List<String> REASONING_KEYWORDS = Arrays.asList(
            "分析", "总结", "为什么", "怎么回事", "对比", "比较", "评估", "评价",
            "建议", "优化", "改进", "原因", "影响", "趋势", "预测", "深度",
            "详细说明", "详细解释", "深入", "综合", "全面"
    );

    // SQL 查询关键词
    private static final List<String> SQL_KEYWORDS = Arrays.asList(
            "查询", "多少", "列表", "统计", "汇总", "总数", "数量", "排名",
            "前几", "最多", "最少", "平均", "总和", "明细", "记录",
            "有多少", "有几个", "查一下", "查查看", "数据"
    );

    // 工具调用关键词（业务数据查询）
    private static final List<String> TOOL_KEYWORDS = Arrays.asList(
            "考勤", "打卡", "人员", "员工", "工人", "班组", "项目", "工程",
            "工资", "薪资", "薪酬", "合同", "协议", "审批", "巡查", "巡检",
            "异常", "告警", "提醒", "通知", "导出", "报表", "报告"
    );

    /**
     * 分类用户问题到任务类型
     *
     * @param userQuestion 用户的自然语言问题
     * @return 任务类型
     */
    public TaskType classify(String userQuestion) {
        if (userQuestion == null || userQuestion.trim().isEmpty()) {
            return TaskType.CHAT;
        }

        String lower = userQuestion.toLowerCase();

        // 优先级 1：图片理解（最高，因为涉及多模态）
        if (containsAny(lower, VISION_KEYWORDS)) {
            log.debug("任务分类: VISION, question={}", userQuestion);
            return TaskType.VISION;
        }

        // 优先级 2：复杂推理
        if (containsAny(lower, REASONING_KEYWORDS)) {
            log.debug("任务分类: COMPLEX_REASONING, question={}", userQuestion);
            return TaskType.COMPLEX_REASONING;
        }

        // 优先级 3：SQL 查询
        if (containsAny(lower, SQL_KEYWORDS)) {
            log.debug("任务分类: SQL_QUERY, question={}", userQuestion);
            return TaskType.SQL_QUERY;
        }

        // 优先级 4：工具调用
        if (containsAny(lower, TOOL_KEYWORDS)) {
            log.debug("任务分类: TOOL_CALLING, question={}", userQuestion);
            return TaskType.TOOL_CALLING;
        }

        // 默认：闲聊
        log.debug("任务分类: CHAT, question={}", userQuestion);
        return TaskType.CHAT;
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

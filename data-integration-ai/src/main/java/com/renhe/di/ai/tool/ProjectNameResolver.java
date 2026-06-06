package com.renhe.di.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.mapper.DiProjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目名称解析器
 * <p>
 * 负责校验用户输入的项目名称，提供项目清单供 AI 智能识别。
 * 不采用写死匹配规则，而是将候选列表交给 AI 大模型判断，
 * 如果 AI 不确定，则返回给用户确认。
 */
@Slf4j
@Component
public class ProjectNameResolver {

    @Resource
    private DiProjectMapper projectMapper;

    /**
     * 解析项目名称
     * <p>
     * 1. 如果名称为空或明显模糊，直接返回提示
     * 2. 否则获取项目清单，连同用户输入一起交给 AI 识别
     * 3. 如果 AI 无法确定唯一匹配，则返回候选列表让用户确认
     *
     * @param projectName 用户输入的项目名称
     * @return ResolveResult：成功返回精确名称，失败返回候选列表和提示信息
     */
    public ResolveResult resolve(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            return ResolveResult.needProjectName("请提供项目名称，例如：查询【XX项目】的考勤情况。");
        }

        String trimmed = projectName.trim();

        // 明显为空的模糊表达，直接拒绝
        if (trimmed.length() < 2 || trimmed.matches("^[全部所有整体集团项目%s%]+$")) {
            return ResolveResult.needProjectName(
                    "【" + trimmed + "】不是一个具体的项目名称，请提供具体的项目名称。");
        }

        // 获取所有项目清单（供 AI 识别）
        List<DiProject> allProjects = projectMapper.selectList(new LambdaQueryWrapper<DiProject>()
                .eq(DiProject::getDeleted, 0)
                .last("LIMIT 500"));

        // 1. 先尝试精确匹配（忽略空格和大小写）
        String normalized = trimmed.replaceAll("\\s+", "").toLowerCase();
        for (DiProject p : allProjects) {
            if (p.getProjectName() != null) {
                String dbNormalized = p.getProjectName().replaceAll("\\s+", "").toLowerCase();
                if (dbNormalized.equals(normalized)) {
                    return ResolveResult.success(p.getProjectName());
                }
            }
        }

        // 2. 精确匹配失败，返回项目清单让 AI 识别
        List<String> allNames = allProjects.stream()
                .map(DiProject::getProjectName)
                .filter(n -> n != null && !n.isEmpty())
                .toList();

        return ResolveResult.needAiMatch(trimmed, allNames);
    }

    /**
     * 解析结果
     */
    public record ResolveResult(
            boolean resolved,
            String exactName,
            String userInput,
            List<String> candidates,
            String message
    ) {
        static ResolveResult success(String exactName) {
            return new ResolveResult(true, exactName, exactName, List.of(), null);
        }

        static ResolveResult needProjectName(String message) {
            return new ResolveResult(false, null, null, List.of(), message);
        }

        /**
         * 需要 AI 进行智能匹配：将项目清单和用户输入一起交给 AI 识别
         */
        static ResolveResult needAiMatch(String userInput, List<String> allProjectNames) {
            StringBuilder sb = new StringBuilder();
            sb.append("用户提到的项目名称为【").append(userInput).append("】，但系统中没有精确匹配的项目。\n");
            sb.append("系统中的项目清单如下，请根据用户描述智能判断最可能的项目：\n\n");
            for (int i = 0; i < allProjectNames.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(allProjectNames.get(i)).append("\n");
            }
            sb.append("\n请根据上下文判断用户指的是哪个项目。");
            sb.append("如果你不确定用户指的是哪个项目，请直接询问用户确认，不要猜测。");
            return new ResolveResult(false, null, userInput, allProjectNames, sb.toString());
        }
    }
}

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
 * 三层匹配策略：
 * 1. 精确匹配（忽略空格和大小写）
 * 2. 子串模糊匹配（用户输入是项目名的子串，或项目名是用户输入的子串）
 * 3. AI 智能识别（将候选清单交给大模型判断）
 */
@Slf4j
@Component
public class ProjectNameResolver {

    @Resource
    private DiProjectMapper projectMapper;

    /**
     * 模糊匹配候选数量上限，超出此数量说明输入太模糊，回退到完整清单
     */
    private static final int MAX_FUZZY_CANDIDATES = 10;

    /**
     * 解析项目名称
     * <p>
     * 三层匹配策略：
     * 1. 精确匹配（忽略空格和大小写）→ 直接返回
     * 2. 子串模糊匹配（contains）→ 唯一命中直接返回，多个命中作为候选交给 AI
     * 3. 全部失败 → 将完整项目清单交给 AI 智能识别
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

        // 获取所有项目清单（@TableLogic 已自动过滤 deleted=0）
        List<DiProject> allProjects = projectMapper.selectList(
                new LambdaQueryWrapper<DiProject>().last("LIMIT 500"));

        log.info("ProjectNameResolver 加载项目数量: {}, 用户输入: [{}]",
                allProjects.size(), trimmed);

        // 调试用：如果数量异常少，打印所有项目名
        if (allProjects.size() < 50) {
            log.info("项目清单: {}", allProjects.stream()
                    .map(DiProject::getProjectName)
                    .filter(n -> n != null)
                    .toList());
        }

        String normalized = trimmed.replaceAll("\\s+", "").toLowerCase();

        // 1. 精确匹配（忽略空格和大小写）
        for (DiProject p : allProjects) {
            if (p.getProjectName() != null) {
                String dbNormalized = p.getProjectName().replaceAll("\\s+", "").toLowerCase();
                if (dbNormalized.equals(normalized)) {
                    return ResolveResult.success(p.getProjectName());
                }
            }
        }

        // 2. 子串模糊匹配：用户输入包含在项目名中，或项目名包含在用户输入中
        List<String> fuzzyMatches = allProjects.stream()
                .map(DiProject::getProjectName)
                .filter(name -> name != null && !name.isEmpty())
                .filter(name -> {
                    String dbNorm = name.replaceAll("\\s+", "").toLowerCase();
                    return dbNorm.contains(normalized) || normalized.contains(dbNorm);
                })
                .toList();

        if (fuzzyMatches.size() == 1) {
            // 唯一命中，直接使用
            log.info("项目名称模糊匹配成功: [{}] -> [{}]", trimmed, fuzzyMatches.get(0));
            return ResolveResult.success(fuzzyMatches.get(0));
        }

        if (fuzzyMatches.size() > 1 && fuzzyMatches.size() <= MAX_FUZZY_CANDIDATES) {
            // 多个命中但在可控范围内，交给 AI 从候选中选择
            log.info("项目名称模糊匹配到 {} 个候选: [{}] -> {}", fuzzyMatches.size(), trimmed, fuzzyMatches);
            return ResolveResult.needAiMatch(trimmed, fuzzyMatches);
        }

        // 3. 全部失败，返回完整项目清单让 AI 识别
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
         * 需要 AI 进行智能匹配：将候选清单和用户输入一起交给 AI 识别
         * <p>
         * 当 candidates 较少时（模糊匹配命中少量候选），AI 更容易判断；
         * 当 candidates 很多时（完整项目清单），AI 需要从中挑选。
         */
        static ResolveResult needAiMatch(String userInput, List<String> candidates) {
            StringBuilder sb = new StringBuilder();
            sb.append("用户提到的项目名称为【").append(userInput).append("】，但系统中没有精确匹配的项目。\n");
            if (candidates.size() <= 10) {
                sb.append("以下是最可能匹配的项目候选，请根据用户描述判断最可能的项目：\n\n");
            } else {
                sb.append("系统中的项目清单如下，请根据用户描述智能判断最可能的项目：\n\n");
            }
            for (int i = 0; i < candidates.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(candidates.get(i)).append("\n");
            }
            sb.append("\n请根据上下文判断用户指的是哪个项目。");
            sb.append("如果你不确定用户指的是哪个项目，请直接询问用户确认，不要猜测。");
            return new ResolveResult(false, null, userInput, candidates, sb.toString());
        }
    }
}

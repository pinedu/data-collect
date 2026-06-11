package com.renhe.di.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.mapper.DiPersonMapper;
import com.renhe.di.store.mapper.DiProjectMapper;
import com.renhe.di.store.mapper.DiTeamMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目查询工具
 * <p>
 * 安全白名单: projectName, projectStatus, areaCode, commencementDate
 */
@Component
public class ProjectTool extends BaseDataTool {

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private DiPersonMapper personMapper;

    @Resource
    private DiTeamMapper teamMapper;

    @Resource
    private ProjectNameResolver projectNameResolver;

    /**
     * 解析用户输入的项目名称
     * <p>
     * 当用户提到项目名但不确定精确名称时调用此工具。
     * 支持模糊匹配：用户输入"花漾"可匹配"投控·花漾云岩A地块"。
     * 返回精确项目名，或候选列表让用户确认。
     */
    @Tool(description = "解析用户输入的项目名称，返回系统中精确匹配的项目名。支持模糊匹配（如用户说'花漾'可匹配'投控·花漾云岩A地块'）。当用户提到项目名但不确定精确名称时，优先调用此工具解析，再使用解析结果调用其他项目相关工具")
    public String resolveProjectName(
            @ToolParam(description = "用户输入的项目名称，可以是简称、部分名或带分隔符的名称") String userInput) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(userInput);
        if (result.resolved()) {
            return "项目名解析成功：【" + result.exactName() + "】";
        }
        return result.message();
    }

    /**
     * 按状态列出项目
     */
    @Tool(description = "按项目状态列出项目，如'在建'、'完工'等。如果不确定状态，传空字符串列出所有项目。当用户提到具体项目名时，优先调用 resolveProjectName 解析名称，而不是列出所有项目")
    public String listProjects(
            @ToolParam(description = "项目状态，如：在建、完工、停工") String status) {

        LambdaQueryWrapper<DiProject> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.like(DiProject::getProjectStatus, status);
        }
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiProject> list = projectMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return (status != null ? "状态为【" + status + "】的项目" : "项目列表") + "为空";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(status != null ? "状态【" + status + "】的项目" : "项目列表").append(":\n");
        for (DiProject p : list) {
            sb.append("  ").append(p.getProjectName())
                    .append(" (").append(p.getProjectStatus() != null ? p.getProjectStatus() : "未知").append(")")
                    .append(", 区域: ").append(p.getAreaCode() != null ? p.getAreaCode() : "-")
                    .append("\n");
        }

        return buildReply(sb.toString());
    }

    /**
     * 项目概况
     */
    @Tool(description = "按项目名称查询项目概况，包括项目基本信息、人员总数和班组数量。项目名称可以是简称或不完整名称，内部会自动解析匹配。如果解析失败返回候选列表让用户确认")
    public String getProjectOverview(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        // 校验项目名称
        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        String exactName = result.exactName();

        LambdaQueryWrapper<DiProject> pw = new LambdaQueryWrapper<>();
        pw.eq(DiProject::getProjectName, exactName);
        pw.last("LIMIT 1");
        DiProject project = projectMapper.selectOne(pw);

        if (project == null) {
            return "未找到项目【" + exactName + "】";
        }

        String sourceProjectNum = project.getSourceProjectNum();

        // 统计人员数（按项目过滤）
        Long personCount = personMapper.selectCount(
                new LambdaQueryWrapper<com.renhe.di.store.entity.DiPerson>()
                        .eq(com.renhe.di.store.entity.DiPerson::getSourceProjectNum, sourceProjectNum)
                        .eq(com.renhe.di.store.entity.DiPerson::getDeleted, 0)
        );
        // 统计班组数（按项目过滤）
        Long teamCount = teamMapper.selectCount(
                new LambdaQueryWrapper<com.renhe.di.store.entity.DiTeam>()
                        .eq(com.renhe.di.store.entity.DiTeam::getSourceProjectNum, sourceProjectNum)
                        .eq(com.renhe.di.store.entity.DiTeam::getDeleted, 0)
        );

        String overview = "项目概况:\n"
                + "  名称: " + project.getProjectName() + "\n"
                + "  状态: " + (project.getProjectStatus() != null ? project.getProjectStatus() : "未知") + "\n"
                + "  区域: " + (project.getAreaCode() != null ? project.getAreaCode() : "-") + "\n"
                + "  备案号: " + (project.getRecordNumber() != null ? project.getRecordNumber() : "-") + "\n"
                + "  总人员: " + personCount + " 人\n"
                + "  总班组: " + teamCount + " 个";

        return buildReply(overview);
    }
}

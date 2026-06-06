package com.renhe.di.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.store.entity.DiPerson;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.entity.DiTeam;
import com.renhe.di.store.mapper.DiPersonMapper;
import com.renhe.di.store.mapper.DiProjectMapper;
import com.renhe.di.store.mapper.DiTeamMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 班组查询工具
 * <p>
 * 安全白名单: teamName, workType, teamStatus, leaderName —— 不返回班组长身份证/电话
 */
@Component
public class TeamTool extends BaseDataTool {

    @Resource
    private DiTeamMapper teamMapper;

    @Resource
    private DiPersonMapper personMapper;

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private ProjectNameResolver projectNameResolver;

    /**
     * 列出项目的班组
     */
    @Tool(description = "列出指定项目的所有班组名称和工种。项目名称必须精确，如果不确定请先调用项目列表工具确认")
    public String listTeams(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        // 校验项目名称
        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        String exactName = result.exactName();

        // 获取项目的 sourceProjectNum
        LambdaQueryWrapper<DiProject> pw = new LambdaQueryWrapper<>();
        pw.eq(DiProject::getProjectName, exactName);
        pw.last("LIMIT 1");
        DiProject project = projectMapper.selectOne(pw);

        if (project == null) {
            return "未找到项目【" + exactName + "】";
        }

        String sourceProjectNum = project.getSourceProjectNum();

        LambdaQueryWrapper<DiTeam> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiTeam::getSourceProjectNum, sourceProjectNum);
        wrapper.eq(DiTeam::getDeleted, 0);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiTeam> list = teamMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return "项目【" + exactName + "】暂无班组记录";
        }

        List<Map<String, Object>> safe = new ArrayList<>();
        for (DiTeam t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamName", t.getTeamName());
            m.put("workType", t.getWorkType());
            m.put("teamStatus", t.getTeamStatus());
            m.put("leaderName", t.getLeaderName());
            safe.add(m);
        }

        return buildReply("项目【" + exactName + "】班组列表:\n" + summarize(safe));
    }

    /**
     * 班组概况（人数+工种分布）
     */
    @Tool(description = "查询班组概况，包括班组基本信息和人员数量")
    public String getTeamOverview(
            @ToolParam(description = "班组名称") String teamName) {

        LambdaQueryWrapper<DiTeam> tw = new LambdaQueryWrapper<>();
        tw.eq(DiTeam::getTeamName, teamName);
        tw.eq(DiTeam::getDeleted, 0);
        tw.last("LIMIT 1");
        DiTeam team = teamMapper.selectOne(tw);

        if (team == null) {
            return "未找到班组【" + teamName + "】";
        }

        // 统计该班组人员
        Long personCount = personMapper.selectCount(
                new LambdaQueryWrapper<DiPerson>()
                        .eq(DiPerson::getTeamName, teamName)
                        .eq(DiPerson::getDeleted, 0)
        );

        String overview = "班组概况:\n"
                + "  名称: " + team.getTeamName() + "\n"
                + "  工种: " + (team.getWorkType() != null ? team.getWorkType() : "-") + "\n"
                + "  状态: " + (team.getTeamStatus() != null ? team.getTeamStatus() : "-") + "\n"
                + "  班组长: " + (team.getLeaderName() != null ? team.getLeaderName() : "-") + "\n"
                + "  人员数: " + personCount + " 人";

        return buildReply(overview);
    }
}

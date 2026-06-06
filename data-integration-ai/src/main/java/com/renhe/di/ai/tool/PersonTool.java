package com.renhe.di.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.store.entity.DiPerson;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.mapper.DiPersonMapper;
import com.renhe.di.store.mapper.DiProjectMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 人员查询工具
 * <p>
 * 安全白名单: personName, workType, teamName, jobStatus —— 绝不返回身份证/手机/银行卡
 */
@Component
public class PersonTool extends BaseDataTool {

    @Resource
    private DiPersonMapper personMapper;

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private ProjectNameResolver projectNameResolver;

    /**
     * 按姓名模糊搜索人员
     */
    @Tool(description = "按姓名模糊搜索人员，只返回姓名、工种、班组、在岗状态，不返回身份证手机等隐私信息")
    public String searchPerson(
            @ToolParam(description = "人员姓名（支持模糊匹配）") String name) {

        LambdaQueryWrapper<DiPerson> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(DiPerson::getPersonName, name);
        wrapper.eq(DiPerson::getDeleted, 0);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiPerson> list = personMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return "未找到姓名包含【" + name + "】的人员";
        }

        List<Map<String, Object>> safe = new ArrayList<>();
        for (DiPerson p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("personName", p.getPersonName());
            m.put("workType", p.getWorkType());
            m.put("teamName", p.getTeamName());
            m.put("jobStatus", p.getJobStatus());
            safe.add(m);
        }

        return buildReply("搜索【" + name + "】结果:\n" + summarize(safe));
    }

    /**
     * 按工种统计人员
     */
    @Tool(description = "按项目名称和工种统计在岗人员数量。项目名称必须精确，如果不确定请先调用项目列表工具确认")
    public String countByWorkType(
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

        LambdaQueryWrapper<DiPerson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiPerson::getDeleted, 0);
        wrapper.eq(DiPerson::getSourceProjectNum, sourceProjectNum);
        wrapper.isNotNull(DiPerson::getWorkType);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiPerson> list = personMapper.selectList(wrapper);

        Map<String, Long> stats = new LinkedHashMap<>();
        for (DiPerson p : list) {
            String wt = p.getWorkType() != null ? p.getWorkType() : "未知";
            stats.merge(wt, 1L, Long::sum);
        }

        StringBuilder sb = new StringBuilder("项目【" + exactName + "】工种人员统计:\n");
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("人\n");
        }

        return buildReply(sb.toString());
    }
}

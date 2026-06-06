package com.renhe.di.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.mapper.DiAttendanceMapper;
import com.renhe.di.store.mapper.DiProjectMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 考勤查询工具
 * <p>
 * 安全白名单: personName, teamName, attendanceTime, attendanceDirection, attendanceWay, jobStatus
 */
@Component
public class AttendanceTool extends BaseDataTool {

    @Resource
    private DiAttendanceMapper attendanceMapper;

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private ProjectNameResolver projectNameResolver;

    private static final Set<String> WHITE_LIST = Set.of(
            "personName", "teamName", "attendanceTime", "attendanceDirection", "attendanceWay", "jobStatus"
    );

    /**
     * 按项目查询今日考勤统计
     */
    @Tool(description = "按项目名称查询今日考勤情况，返回出勤人数和出勤率。项目名称必须精确，如果不确定请先调用项目列表工具确认")
    public String queryAttendanceByProject(
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

        LambdaQueryWrapper<DiAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiAttendance::getSourceProjectNum, sourceProjectNum);
        wrapper.ge(DiAttendance::getAttendanceTime, LocalDate.now().atStartOfDay());
        wrapper.le(DiAttendance::getAttendanceTime, LocalDate.now().plusDays(1).atStartOfDay());
        wrapper.eq(DiAttendance::getDeleted, 0);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiAttendance> list = attendanceMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return "项目【" + exactName + "】今日暂无考勤记录";
        }

        long inCount = list.stream()
                .filter(a -> "进场".equals(a.getAttendanceDirection()))
                .count();
        long outCount = list.size() - inCount;

        return buildReply("项目【" + exactName + "】今日考勤: 共 " + list.size()
                + " 条记录, 进场 " + inCount + " 人次, 出场 " + outCount + " 人次");
    }

    /**
     * 按人员查询最近 N 天考勤
     */
    @Tool(description = "按人员姓名查询最近N天的考勤摘要，默认7天")
    public String queryAttendanceByPerson(
            @ToolParam(description = "人员姓名") String personName,
            @ToolParam(description = "查询天数，默认7天") Integer days) {

        int d = (days != null && days > 0 && days <= 30) ? days : 7;

        LambdaQueryWrapper<DiAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiAttendance::getPersonName, personName);
        wrapper.ge(DiAttendance::getAttendanceTime, LocalDateTime.now().minusDays(d));
        wrapper.eq(DiAttendance::getDeleted, 0);
        wrapper.orderByDesc(DiAttendance::getAttendanceTime);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiAttendance> list = attendanceMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return "【" + personName + "】最近 " + d + " 天暂无考勤记录";
        }

        // 转为 Map 并脱敏
        List<Map<String, Object>> maps = new ArrayList<>();
        for (DiAttendance a : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("personName", a.getPersonName());
            m.put("teamName", a.getTeamName());
            m.put("attendanceTime", a.getAttendanceTime() != null
                    ? a.getAttendanceTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) : null);
            m.put("attendanceDirection", a.getAttendanceDirection());
            maps.add(m);
        }

        return buildReply("【" + personName + "】最近 " + d + " 天考勤摘要:\n" + summarize(maps));
    }

    /**
     * 按项目+日期统计各班组出勤人数
     */
    @Tool(description = "按项目和日期查询各班组出勤人数统计。项目名称必须精确，如果不确定请先调用项目列表工具确认")
    public String queryAttendanceTeamStats(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName,
            @ToolParam(description = "查询日期，格式yyyy-MM-dd") String date) {

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

        LocalDate queryDate;
        try {
            queryDate = LocalDate.parse(date);
        } catch (Exception e) {
            queryDate = LocalDate.now();
        }

        LambdaQueryWrapper<DiAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiAttendance::getSourceProjectNum, sourceProjectNum);
        wrapper.ge(DiAttendance::getAttendanceTime, queryDate.atStartOfDay());
        wrapper.le(DiAttendance::getAttendanceTime, queryDate.plusDays(1).atStartOfDay());
        wrapper.eq(DiAttendance::getDeleted, 0);
        wrapper.last("LIMIT " + MAX_ROWS);

        List<DiAttendance> list = attendanceMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return "项目【" + exactName + "】在 " + date + " 暂无考勤记录";
        }

        // 按班组统计
        Map<String, Long> teamCounts = new LinkedHashMap<>();
        for (DiAttendance a : list) {
            String team = a.getTeamName() != null ? a.getTeamName() : "未知班组";
            teamCounts.merge(team, 1L, Long::sum);
        }

        StringBuilder sb = new StringBuilder("项目【" + exactName + "】" + date + " 各班组出勤:\n");
        for (Map.Entry<String, Long> e : teamCounts.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("人\n");
        }

        return buildReply(sb.toString());
    }
}

package com.renhe.di.ai.tool;

import com.renhe.di.store.entity.DiProject;
import com.renhe.di.store.entity.DiProjectMonthlySalaryAttendanceStats;
import com.renhe.di.store.entity.DiProjectSalaryAttendanceDetail;
import com.renhe.di.store.mapper.DiProjectMapper;
import com.renhe.di.store.service.DiProjectMonthlySalaryAttendanceStatsService;
import com.renhe.di.store.service.DiProjectSalaryAttendanceDetailService;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 工资考勤交叉分析工具
 * <p>
 * 查询项目月度工资考勤统计和逐人明细，识别"有工资无考勤"、"有考勤无工资"等异常数据。
 */
@Component
public class SalaryAttendanceTool extends BaseDataTool {

    @Resource
    private ProjectNameResolver projectNameResolver;

    @Resource
    private DiProjectMapper projectMapper;

    @Resource
    private DiProjectMonthlySalaryAttendanceStatsService statsService;

    @Resource
    private DiProjectSalaryAttendanceDetailService detailService;

    /**
     * 按年份查询项目月度工资考勤统计
     */
    @Tool(description = "查询指定项目某年份的每月工资发放和考勤统计数据，包括发放金额、人数、考勤次数及交叉对比")
    public String getMonthlySalaryStats(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName,
            @ToolParam(description = "年份，如2025、2026") String year) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }
        String sourceProjectNum = project.getSourceProjectNum();

        var query = statsService.lambdaQuery()
                .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, sourceProjectNum)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichYear)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichMonth);

        if (year != null && !year.isBlank()) {
            query.eq(DiProjectMonthlySalaryAttendanceStats::getWhichYear, year);
        }

        List<DiProjectMonthlySalaryAttendanceStats> stats = query.list();

        if (stats.isEmpty()) {
            return "项目【" + result.exactName() + "】" + (year != null ? year + "年" : "") + "暂无工资考勤统计数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" 月度工资考勤统计\n\n");

        String currentYear = null;
        BigDecimal yearTotal = BigDecimal.ZERO;
        int yearPayCount = 0;
        int yearAttendCount = 0;

        for (DiProjectMonthlySalaryAttendanceStats s : stats) {
            // 年份切换输出小计
            if (currentYear != null && !currentYear.equals(s.getWhichYear())) {
                sb.append("  **").append(currentYear).append("年小计**: 发放 ").append(yearTotal).append("元, ")
                        .append(yearPayCount).append("人次, 考勤 ").append(yearAttendCount).append("人次\n\n");
                yearTotal = BigDecimal.ZERO;
                yearPayCount = 0;
                yearAttendCount = 0;
            }
            currentYear = s.getWhichYear();

            sb.append(s.getWhichMonth()).append("月: ");
            if (s.getPayAmount() != null) {
                sb.append("发放 ").append(s.getPayAmount()).append("元/").append(s.getPayPersonNum() != null ? s.getPayPersonNum() : 0).append("人");
                yearTotal = yearTotal.add(s.getPayAmount());
                if (s.getPayPersonNum() != null) yearPayCount += s.getPayPersonNum();
            } else {
                sb.append("未发放");
            }
            sb.append(" | 考勤 ").append(s.getAttendTimes() != null ? s.getAttendTimes() : 0).append("次/");
            sb.append(s.getAttendPersonNum() != null ? s.getAttendPersonNum() : 0).append("人");
            if (s.getAttendPersonNum() != null) yearAttendCount += s.getAttendPersonNum();
            sb.append(" | ").append(s.getPayType() != null ? s.getPayType() : "-");
            sb.append("\n");
        }

        // 最后一年小计
        if (currentYear != null) {
            sb.append("  **").append(currentYear).append("年小计**: 发放 ").append(yearTotal).append("元, ")
                    .append(yearPayCount).append("人次, 考勤 ").append(yearAttendCount).append("人次\n");
        }

        return buildReply(sb.toString());
    }

    /**
     * 查询工资考勤异常记录（有工资无考勤 / 有考勤无工资）
     */
    @Tool(description = "查询指定项目的工资考勤交叉异常，列出有工资无考勤和有考勤无工资的月份及人数")
    public String getAbnormalRecords(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }
        String sourceProjectNum = project.getSourceProjectNum();

        List<DiProjectMonthlySalaryAttendanceStats> stats = statsService.lambdaQuery()
                .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, sourceProjectNum)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichYear)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichMonth)
                .list();

        if (stats.isEmpty()) {
            return "项目【" + result.exactName() + "】暂无工资考勤统计数据";
        }

        List<String> salaryNoAttend = new ArrayList<>();
        List<String> attendNoSalary = new ArrayList<>();

        for (DiProjectMonthlySalaryAttendanceStats s : stats) {
            String month = s.getWhichYear() + "-" + s.getWhichMonth();

            if (s.getSalaryNoAttendNum() != null && s.getSalaryNoAttendNum().compareTo(BigDecimal.ZERO) > 0) {
                salaryNoAttend.add(month + ": " + s.getSalaryNoAttendNum() + "人");
            }
            if (s.getAttendNoSalaryNum() != null && s.getAttendNoSalaryNum().compareTo(BigDecimal.ZERO) > 0) {
                attendNoSalary.add(month + ": " + s.getAttendNoSalaryNum() + "人");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" 工资考勤异常\n\n");

        sb.append("### ⚠️ 有工资无考勤\n");
        if (salaryNoAttend.isEmpty()) {
            sb.append("  无异常\n");
        } else {
            for (String line : salaryNoAttend) {
                sb.append("  ❌ ").append(line).append("\n");
            }
        }

        sb.append("\n### ⚠️ 有考勤无工资\n");
        if (attendNoSalary.isEmpty()) {
            sb.append("  无异常\n");
        } else {
            for (String line : attendNoSalary) {
                sb.append("  ❌ ").append(line).append("\n");
            }
        }

        // 正常月份统计
        List<String> normal = new ArrayList<>();
        for (DiProjectMonthlySalaryAttendanceStats s : stats) {
            boolean hasSalaryAbnormal = s.getSalaryNoAttendNum() != null && s.getSalaryNoAttendNum().compareTo(BigDecimal.ZERO) > 0;
            boolean hasAttendAbnormal = s.getAttendNoSalaryNum() != null && s.getAttendNoSalaryNum().compareTo(BigDecimal.ZERO) > 0;
            if (!hasSalaryAbnormal && !hasAttendAbnormal) {
                String month = s.getWhichYear() + "-" + s.getWhichMonth();
                normal.add(month + ": 有工资有考勤 " + (s.getSalaryAttendNum() != null ? s.getSalaryAttendNum() : 0) + "人");
            }
        }

        sb.append("\n### ✅ 正常月份\n");
        if (normal.isEmpty()) {
            sb.append("  无\n");
        } else {
            for (String line : normal) {
                sb.append("  ").append(line).append("\n");
            }
        }

        return buildReply(sb.toString());
    }

    /**
     * 查询指定项目某月的逐人考勤明细
     */
    @Tool(description = "查询指定项目某月的工资考勤逐人明细，按班组分组展示每人的考勤天数")
    public String getAttendanceDetail(
            @ToolParam(description = "项目名称（必须精确匹配）") String projectName,
            @ToolParam(description = "统计月份，格式YYYY-MM，如2025-03") String dateMonth) {

        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return result.message();
        }
        DiProject project = findProjectByName(result.exactName());
        if (project == null) {
            return "未找到项目【" + result.exactName() + "】";
        }
        String sourceProjectNum = project.getSourceProjectNum();

        if (dateMonth == null || dateMonth.isBlank()) {
            return "请提供统计月份，格式如 2025-03";
        }

        List<DiProjectSalaryAttendanceDetail> details = detailService.lambdaQuery()
                .eq(DiProjectSalaryAttendanceDetail::getSourceProjectNum, sourceProjectNum)
                .eq(DiProjectSalaryAttendanceDetail::getDateMonth, dateMonth)
                .orderByAsc(DiProjectSalaryAttendanceDetail::getTeamName)
                .orderByAsc(DiProjectSalaryAttendanceDetail::getPersonName)
                .list();

        if (details.isEmpty()) {
            return "项目【" + result.exactName() + "】" + dateMonth + " 暂无考勤明细数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.exactName()).append(" ").append(dateMonth).append(" 考勤明细\n");
        sb.append("共 ").append(details.size()).append(" 人\n\n");

        String currentTeam = null;
        int teamCount = 0;

        for (DiProjectSalaryAttendanceDetail d : details) {
            // 班组分组
            if (!d.getTeamName().equals(currentTeam)) {
                if (currentTeam != null) {
                    sb.append("  小计: ").append(teamCount).append("人\n\n");
                }
                currentTeam = d.getTeamName();
                teamCount = 0;
                sb.append("### ").append(currentTeam).append("\n");
            }
            teamCount++;
            sb.append("  ").append(d.getPersonName())
                    .append(": ").append(d.getAttDayNum() != null ? d.getAttDayNum() : 0).append("天\n");
        }
        // 最后一个班组小计
        if (currentTeam != null) {
            sb.append("  小计: ").append(teamCount).append("人\n");
        }

        return buildReply(sb.toString());
    }

    private DiProject findProjectByName(String exactName) {
        return projectMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DiProject>()
                        .eq(DiProject::getProjectName, exactName)
                        .last("LIMIT 1"));
    }
}

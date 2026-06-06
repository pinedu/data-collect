package com.renhe.di.bootstrap.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.renhe.di.core.model.Result;
import com.renhe.di.api.vo.SyncDashboardItemVO;
import com.renhe.di.store.entity.*;
import com.renhe.di.store.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据查询Controller
 * 提供各维度数据的分页查询和统计报表接口
 */
@Slf4j
@RestController
@RequestMapping("/data")
public class DataQueryController {

    @Autowired
    private DiProjectService projectService;

    @Autowired
    private DiContractorService contractorService;

    @Autowired
    private DiTeamService teamService;

    @Autowired
    private DiPersonService personService;

    @Autowired
    private DiAttendanceService attendanceService;

    @Autowired
    private DiPayrollService payrollService;

    @Autowired
    private DiPayrollDetailService payrollDetailService;

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    // ==================== 项目信息查询 ====================

    /**
     * 构建 sourceProjectNum -> projectName 映射
     */
    private Map<String, String> getProjectNameMap() {
        return projectService.lambdaQuery()
                .eq(DiProject::getDeleted, 0)
                .select(DiProject::getSourceProjectNum, DiProject::getProjectName)
                .list()
                .stream()
                .collect(Collectors.toMap(DiProject::getSourceProjectNum, DiProject::getProjectName, (a, b) -> a));
    }

    @GetMapping("/project/list")
    public Result<IPage<DiProject>> listProjects(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String projectName) {
        LambdaQueryWrapper<DiProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiProject::getDeleted, 0);
        if (projectName != null && !projectName.isEmpty()) {
            wrapper.like(DiProject::getProjectName, projectName);
        }
        wrapper.orderByDesc(DiProject::getUpdatedAt);
        return Result.success(projectService.page(new Page<>(pageNum, pageSize), wrapper));
    }

    @GetMapping("/project/{sourceProjectNum}")
    public Result<DiProject> getProject(@PathVariable String sourceProjectNum) {
        return Result.success(projectService.lambdaQuery()
                .eq(DiProject::getSourceProjectNum, sourceProjectNum)
                .eq(DiProject::getDeleted, 0)
                .one());
    }

    /**
     * 项目下拉选项（轻量级，仅返回编号+名称，支持关键字模糊搜索）
     */
    @GetMapping("/project/options")
    public Result<List<Map<String, String>>> searchProjectOptions(
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<DiProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiProject::getDeleted, 0);
        wrapper.select(DiProject::getSourceProjectNum, DiProject::getProjectName);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(DiProject::getProjectName, keyword);
        }
        wrapper.orderByDesc(DiProject::getUpdatedAt);
        wrapper.last("LIMIT 50");
        List<DiProject> list = projectService.list(wrapper);
        List<Map<String, String>> options = new ArrayList<>();
        for (DiProject p : list) {
            Map<String, String> option = new HashMap<>();
            option.put("sourceProjectNum", p.getSourceProjectNum());
            option.put("projectName", p.getProjectName());
            options.add(option);
        }
        return Result.success(options);
    }

    // ==================== 班组信息查询 ====================

    @GetMapping("/team/list")
    public Result<IPage<DiTeam>> listTeams(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceProjectNum,
            @RequestParam(required = false) String teamName) {
        LambdaQueryWrapper<DiTeam> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiTeam::getDeleted, 0);
        if (sourceProjectNum != null && !sourceProjectNum.isEmpty()) {
            wrapper.eq(DiTeam::getSourceProjectNum, sourceProjectNum);
        }
        if (teamName != null && !teamName.isEmpty()) {
            wrapper.like(DiTeam::getTeamName, teamName);
        }
        wrapper.orderByDesc(DiTeam::getUpdatedAt);
        IPage<DiTeam> pageResult = teamService.page(new Page<>(pageNum, pageSize), wrapper);
        Map<String, String> nameMap = getProjectNameMap();
        pageResult.getRecords().forEach(r -> r.setProjectName(nameMap.get(r.getSourceProjectNum())));
        return Result.success(pageResult);
    }

    // ==================== 人员信息查询 ====================

    @GetMapping("/person/list")
    public Result<IPage<DiPerson>> listPersons(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceProjectNum,
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String idCardNo) {
        LambdaQueryWrapper<DiPerson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiPerson::getDeleted, 0);
        if (sourceProjectNum != null && !sourceProjectNum.isEmpty()) {
            wrapper.eq(DiPerson::getSourceProjectNum, sourceProjectNum);
        }
        if (personName != null && !personName.isEmpty()) {
            wrapper.like(DiPerson::getPersonName, personName);
        }
        if (idCardNo != null && !idCardNo.isEmpty()) {
            wrapper.like(DiPerson::getIdCardNo, idCardNo);
        }
        wrapper.orderByDesc(DiPerson::getUpdatedAt);
        IPage<DiPerson> pageResult = personService.page(new Page<>(pageNum, pageSize), wrapper);
        Map<String, String> nameMap = getProjectNameMap();
        pageResult.getRecords().forEach(r -> r.setProjectName(nameMap.get(r.getSourceProjectNum())));
        return Result.success(pageResult);
    }

    // ==================== 考勤记录查询 ====================

    @GetMapping("/attendance/list")
    public Result<IPage<DiAttendance>> listAttendance(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceProjectNum,
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate) {
        LambdaQueryWrapper<DiAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiAttendance::getDeleted, 0);
        if (sourceProjectNum != null && !sourceProjectNum.isEmpty()) {
            wrapper.eq(DiAttendance::getSourceProjectNum, sourceProjectNum);
        }
        if (personName != null && !personName.isEmpty()) {
            wrapper.like(DiAttendance::getPersonName, personName);
        }
        if (beginDate != null && !beginDate.isEmpty()) {
            wrapper.ge(DiAttendance::getAttendanceTime, LocalDate.parse(beginDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(DiAttendance::getAttendanceTime, LocalDate.parse(endDate).atTime(23, 59, 59));
        }
        wrapper.orderByDesc(DiAttendance::getAttendanceTime);
        IPage<DiAttendance> pageResult = attendanceService.page(new Page<>(pageNum, pageSize), wrapper);
        Map<String, String> nameMap = getProjectNameMap();
        pageResult.getRecords().forEach(r -> r.setProjectName(nameMap.get(r.getSourceProjectNum())));
        return Result.success(pageResult);
    }

    // ==================== 工资记录查询 ====================

    @GetMapping("/payroll/list")
    public Result<IPage<DiPayroll>> listPayrolls(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceProjectNum,
            @RequestParam(required = false) String salaryMonth) {
        LambdaQueryWrapper<DiPayroll> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiPayroll::getDeleted, 0);
        if (sourceProjectNum != null && !sourceProjectNum.isEmpty()) {
            wrapper.eq(DiPayroll::getSourceProjectNum, sourceProjectNum);
        }
        if (salaryMonth != null && !salaryMonth.isEmpty()) {
            wrapper.eq(DiPayroll::getSalaryMonth, salaryMonth);
        }
        wrapper.orderByDesc(DiPayroll::getUpdatedAt);
        IPage<DiPayroll> pageResult = payrollService.page(new Page<>(pageNum, pageSize), wrapper);
        Map<String, String> nameMap = getProjectNameMap();
        pageResult.getRecords().forEach(r -> r.setProjectName(nameMap.get(r.getSourceProjectNum())));
        return Result.success(pageResult);
    }

    @GetMapping("/payroll/detail/list")
    public Result<IPage<DiPayrollDetail>> listPayrollDetails(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String salaryId,
            @RequestParam(required = false) String personName) {
        LambdaQueryWrapper<DiPayrollDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiPayrollDetail::getDeleted, 0);
        if (salaryId != null && !salaryId.isEmpty()) {
            wrapper.eq(DiPayrollDetail::getSalaryId, salaryId);
        }
        if (personName != null && !personName.isEmpty()) {
            wrapper.like(DiPayrollDetail::getPersonName, personName);
        }
        wrapper.orderByDesc(DiPayrollDetail::getUpdatedAt);
        return Result.success(payrollDetailService.page(new Page<>(pageNum, pageSize), wrapper));
    }

    // ==================== 统计报表 ====================

    @GetMapping("/statistics/overview")
    public Result<Map<String, Object>> getOverview() {
        Map<String, Object> result = new HashMap<>();

        long projectCount = projectService.lambdaQuery().eq(DiProject::getDeleted, 0).count();
        long contractorCount = contractorService.lambdaQuery().eq(DiContractor::getDeleted, 0).count();
        long teamCount = teamService.lambdaQuery().eq(DiTeam::getDeleted, 0).count();
        long personCount = personService.lambdaQuery().eq(DiPerson::getDeleted, 0).count();
        long attendanceCount = attendanceService.lambdaQuery().eq(DiAttendance::getDeleted, 0).count();
        long payrollCount = payrollService.lambdaQuery().eq(DiPayroll::getDeleted, 0).count();

        result.put("projectCount", projectCount);
        result.put("contractorCount", contractorCount);
        result.put("teamCount", teamCount);
        result.put("personCount", personCount);
        result.put("attendanceCount", attendanceCount);
        result.put("payrollCount", payrollCount);

        return Result.success(result);
    }

    @GetMapping("/statistics/project/{sourceProjectNum}")
    public Result<Map<String, Object>> getProjectStatistics(@PathVariable String sourceProjectNum) {
        Map<String, Object> result = new HashMap<>();

        long teamCount = teamService.lambdaQuery()
                .eq(DiTeam::getSourceProjectNum, sourceProjectNum)
                .eq(DiTeam::getDeleted, 0)
                .count();
        long personCount = personService.lambdaQuery()
                .eq(DiPerson::getSourceProjectNum, sourceProjectNum)
                .eq(DiPerson::getDeleted, 0)
                .count();
        long attendanceCount = attendanceService.lambdaQuery()
                .eq(DiAttendance::getSourceProjectNum, sourceProjectNum)
                .eq(DiAttendance::getDeleted, 0)
                .count();
        long payrollCount = payrollService.lambdaQuery()
                .eq(DiPayroll::getSourceProjectNum, sourceProjectNum)
                .eq(DiPayroll::getDeleted, 0)
                .count();

        result.put("sourceProjectNum", sourceProjectNum);
        result.put("teamCount", teamCount);
        result.put("personCount", personCount);
        result.put("attendanceCount", attendanceCount);
        result.put("payrollCount", payrollCount);

        return Result.success(result);
    }

    // ==================== 项目配置查询 ====================

    @GetMapping("/project-config/list")
    public Result<List<DiProjectConfig>> listProjectConfigs() {
        return Result.success(projectConfigService.getAllActiveQxbProjects());
    }

    // ==================== 同步数据看板 ====================

    /**
     * 同步数据看板 - 按项目查询各业务数据对比
     */
    @GetMapping("/sync-dashboard/{sourceProjectNum}")
    public Result<List<SyncDashboardItemVO>> getSyncDashboard(@PathVariable String sourceProjectNum) {
        List<DiSyncSnapshot> snapshots = syncSnapshotService.getLatestByProject(sourceProjectNum);

        // 数据类型中文名映射
        Map<String, String> dataTypeNameMap = new HashMap<>();
        dataTypeNameMap.put("TEAM", "班组");
        dataTypeNameMap.put("PERSON", "人员");
        dataTypeNameMap.put("ATTENDANCE", "考勤");
        dataTypeNameMap.put("PAYROLL", "工资");
        dataTypeNameMap.put("PAYROLL_DETAIL", "工资明细");

        // 默认的 dataType 顺序
        List<String> orderedTypes = List.of("TEAM", "PERSON", "ATTENDANCE", "PAYROLL");

        Map<String, SyncDashboardItemVO> snapshotMap = new HashMap<>();
        for (DiSyncSnapshot s : snapshots) {
            String type = s.getDataType();
            String diffRate = "-";
            if (s.getThirdPartyTotal() != null && s.getThirdPartyTotal() > 0) {
                double rate = (double) s.getLocalTotal() * 100 / s.getThirdPartyTotal();
                diffRate = String.format("%.1f%%", rate);
            }
            snapshotMap.put(type, SyncDashboardItemVO.builder()
                    .dataType(type)
                    .dataTypeName(dataTypeNameMap.getOrDefault(type, type))
                    .thirdPartyTotal(s.getThirdPartyTotal())
                    .localTotal(s.getLocalTotal())
                    .lastSyncTime(s.getSyncTime())
                    .diffRate(diffRate)
                    .build());
        }

        // 按固定顺序返回，没有快照的也返回（数据为0）
        List<SyncDashboardItemVO> result = new ArrayList<>();
        for (String type : orderedTypes) {
            SyncDashboardItemVO item = snapshotMap.get(type);
            if (item != null) {
                result.add(item);
            } else {
                result.add(SyncDashboardItemVO.builder()
                        .dataType(type)
                        .dataTypeName(dataTypeNameMap.getOrDefault(type, type))
                        .thirdPartyTotal(0)
                        .localTotal(0)
                        .lastSyncTime(null)
                        .diffRate("-")
                        .build());
            }
        }

        return Result.success(result);
    }
}

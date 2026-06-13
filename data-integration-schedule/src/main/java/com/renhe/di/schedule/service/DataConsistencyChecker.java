package com.renhe.di.schedule.service;

import com.renhe.di.collect.api.AntiCrawlerDetector;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.store.entity.DiPayroll;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.DiAttendanceService;
import com.renhe.di.store.service.DiPayrollService;
import com.renhe.di.store.service.DiProjectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据一致性校验器
 * 校验本地数据与第三方平台数据的一致性
 */
@Slf4j
@Component
public class DataConsistencyChecker {

    @Autowired
    private DiProjectConfigService projectConfigService;

    @Autowired
    private DiAttendanceService attendanceService;

    @Autowired
    private DiPayrollService payrollService;

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private TokenManager tokenManager;

    /**
     * 校验指定项目的考勤数据一致性
     *
     * @param sourceProjectNum 源项目编号
     * @return 校验报告
     */
    public CheckReport checkAttendanceConsistency(String sourceProjectNum) {
        DiProjectConfig config = projectConfigService.getBySourceProjectNum(sourceProjectNum);
        if (config == null) {
            return CheckReport.fail("项目配置不存在");
        }

        log.info("开始校验项目【{}】的考勤数据一致性", sourceProjectNum);

        // 1. 获取第三方平台数据量（直接调用API，异常可透传）
        int remoteCount;
        try {
            remoteCount = apiClient.getAttendancePage(
                    sourceProjectNum, 1, 1,
                    LocalDateTime.of(2020, 1, 1, 0, 0).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    config.getAccount(), config.getPassword()
            ).getInt("total", 0);
        } catch (Exception e) {
            if (AntiCrawlerDetector.isTokenExpired(e)) {
                log.error("项目【{}】考勤一致性校验时Token已过期（401），立即移除Token", sourceProjectNum);
                tokenManager.removeToken(config.getAccount());
                return CheckReport.fail("Token已过期（401登录过期），已移除Token，请重新扫码");
            }
            log.error("项目【{}】考勤一致性校验获取第三方数据失败: {}", sourceProjectNum, e.getMessage());
            return CheckReport.fail("获取第三方数据失败: " + e.getMessage());
        }

        // 2. 获取本地数据量
        int localCount = attendanceService.lambdaQuery()
                .eq(DiAttendance::getSourceProjectNum, sourceProjectNum)
                .count().intValue();

        // 3. 比对
        boolean consistent = remoteCount == localCount;
        String detail = String.format("第三方平台：%d条，本地：%d条，差异：%d条",
                remoteCount, localCount, Math.abs(remoteCount - localCount));

        log.info("项目【{}】考勤数据一致性校验结果：{} - {}",
                sourceProjectNum, consistent ? "一致" : "不一致", detail);

        return new CheckReport(consistent, detail, remoteCount, localCount);
    }

    /**
     * 校验指定项目的工资数据一致性
     */
    public CheckReport checkPayrollConsistency(String sourceProjectNum) {
        DiProjectConfig config = projectConfigService.getBySourceProjectNum(sourceProjectNum);
        if (config == null) {
            return CheckReport.fail("项目配置不存在");
        }

        log.info("开始校验项目【{}】的工资数据一致性", sourceProjectNum);

        // 获取第三方平台数据量（直接调用API，异常可透传）
        int remoteCount;
        try {
            remoteCount = apiClient.getPayrollPage(
                    sourceProjectNum, 1, 1, "",
                    config.getAccount(), config.getPassword()
            ).getInt("total", 0);
        } catch (Exception e) {
            if (AntiCrawlerDetector.isTokenExpired(e)) {
                log.error("项目【{}】工资一致性校验时Token已过期（401），立即移除Token", sourceProjectNum);
                tokenManager.removeToken(config.getAccount());
                return CheckReport.fail("Token已过期（401登录过期），已移除Token，请重新扫码");
            }
            log.error("项目【{}】工资一致性校验获取第三方数据失败: {}", sourceProjectNum, e.getMessage());
            return CheckReport.fail("获取第三方数据失败: " + e.getMessage());
        }

        // 获取本地数据量
        int localCount = payrollService.lambdaQuery()
                .eq(DiPayroll::getSourceProjectNum, sourceProjectNum)
                .count().intValue();

        boolean consistent = remoteCount == localCount;
        String detail = String.format("第三方平台：%d条，本地：%d条，差异：%d条",
                remoteCount, localCount, Math.abs(remoteCount - localCount));

        log.info("项目【{}】工资数据一致性校验结果：{} - {}",
                sourceProjectNum, consistent ? "一致" : "不一致", detail);

        return new CheckReport(consistent, detail, remoteCount, localCount);
    }

    /**
     * 全量校验所有项目
     */
    public Map<String, List<CheckReport>> checkAll() {
        List<DiProjectConfig> projects = projectConfigService.getAllActiveQxbProjects();

        return projects.parallelStream()
                .collect(Collectors.toMap(
                        DiProjectConfig::getSourceProjectNum,
                        p -> List.of(
                                checkAttendanceConsistency(p.getSourceProjectNum()),
                                checkPayrollConsistency(p.getSourceProjectNum())
                        )
                ));
    }

    /**
     * 校验报告
     */
    public record CheckReport(boolean consistent, String detail,
                               int remoteCount, int localCount) {
        public static CheckReport fail(String message) {
            return new CheckReport(false, message, 0, 0);
        }
    }
}

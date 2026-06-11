package com.renhe.di.schedule.job;

import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.parser.AttendanceExcelParser;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiProjectMonthlySalaryAttendanceStats;
import com.renhe.di.store.entity.DiProjectSalaryAttendanceDetail;
import com.renhe.di.store.service.DiProjectMonthlySalaryAttendanceStatsService;
import com.renhe.di.store.service.DiProjectSalaryAttendanceDetailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 项目工资考勤统计明细同步任务
 * 下载各月考勤Excel并解析，存入明细表
 * 依赖 SalaryAttendanceStatsSyncJob 先执行（从统计表获取可用月份）
 */
@Slf4j
@Component
public class SalaryAttendanceDetailSyncJob extends AbstractSyncJob {

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private DiProjectMonthlySalaryAttendanceStatsService statsService;

    @Autowired
    private DiProjectSalaryAttendanceDetailService detailService;

    @Override
    protected String getDataType() {
        return "SALARY_ATTENDANCE_DETAIL";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "工资考勤明细同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return SyncResult.empty();
    }

    /**
     * 同步单个项目的工资考勤明细（由 Pipeline 调用）
     * 从统计表获取需要拿明细的可用月份，逐月下载Excel并解析
     */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String account = project.getAccount();
        String password = project.getPassword();

        // 从统计表获取该项目所有已有数据的月份
        List<DiProjectMonthlySalaryAttendanceStats> statsMonths = statsService.lambdaQuery()
                .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, projectNum)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichYear)
                .orderByAsc(DiProjectMonthlySalaryAttendanceStats::getWhichMonth)
                .list();

        if (statsMonths.isEmpty()) {
            log.info("项目【{}】统计表无数据，跳过明细同步", projectNum);
            return SyncResult.empty();
        }

        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (DiProjectMonthlySalaryAttendanceStats stats : statsMonths) {
            String yearMonth = stats.getWhichYear() + stats.getWhichMonth();  // "202501"
            String dateMonth = stats.getWhichYear() + "-" + stats.getWhichMonth(); // "2025-01"

            try {
                // 下载Excel
                byte[] excelBytes = apiClient.downloadAttendanceList(projectNum, yearMonth, account, password);

                // 解析Excel
                List<Map<String, String>> records = AttendanceExcelParser.parse(excelBytes, projectNum, dateMonth);
                if (records.isEmpty()) {
                    log.info("项目【{}】月份【{}】Excel解析无数据", projectNum, dateMonth);
                    skipCount++;
                    continue;
                }

                // 逐条保存（存在则更新，不存在则插入）
                for (Map<String, String> record : records) {
                    totalCount++;
                    String teamName = record.get("teamName");
                    String personName = record.get("personName");
                    String attDayNumStr = record.get("attDayNum");

                    try {
                        DiProjectSalaryAttendanceDetail existing = detailService.lambdaQuery()
                                .eq(DiProjectSalaryAttendanceDetail::getSourceProjectNum, projectNum)
                                .eq(DiProjectSalaryAttendanceDetail::getDateMonth, dateMonth)
                                .eq(DiProjectSalaryAttendanceDetail::getTeamName, teamName)
                                .eq(DiProjectSalaryAttendanceDetail::getPersonName, personName)
                                .one();

                        int attDayNum = parseIntSafe(attDayNumStr);

                        if (existing != null) {
                            existing.setAttDayNum(attDayNum);
                            detailService.updateById(existing);
                        } else {
                            DiProjectSalaryAttendanceDetail entity = new DiProjectSalaryAttendanceDetail();
                            entity.setSourceProjectNum(projectNum);
                            entity.setDateMonth(dateMonth);
                            entity.setTeamName(teamName);
                            entity.setPersonName(personName);
                            entity.setAttDayNum(attDayNum);
                            detailService.save(entity);
                        }
                        successCount++;
                    } catch (Exception e) {
                        failCount++;
                        log.error("项目【{}】月份【{}】明细数据保存失败 [{}-{}]: {}",
                                projectNum, dateMonth, teamName, personName, e.getMessage());
                    }
                }

                log.info("项目【{}】月份【{}】明细同步完成，解析{}条", projectNum, dateMonth, records.size());
            } catch (Exception e) {
                log.error("项目【{}】月份【{}】明细同步失败: {}", projectNum, dateMonth, e.getMessage(), e);
                if (isAntiCrawlerMessage(e)) {
                    return SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount);
                }
                failCount++;
            }
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    private int parseIntSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value.contains(".") ? value.substring(0, value.indexOf('.')) : value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

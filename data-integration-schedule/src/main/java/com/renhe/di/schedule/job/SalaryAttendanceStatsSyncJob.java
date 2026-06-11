package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiProjectMonthlySalaryAttendanceStats;
import com.renhe.di.store.service.DiProjectMonthlySalaryAttendanceStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目月份工资考勤统计同步任务
 * 调用 getSalaryAndAttendStats 接口，按年采集各月工资考勤交叉统计
 */
@Slf4j
@Component
public class SalaryAttendanceStatsSyncJob extends AbstractSyncJob {

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private DiProjectMonthlySalaryAttendanceStatsService statsService;

    @Override
    protected String getDataType() {
        return "SALARY_ATTENDANCE_STATS";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "工资考勤统计同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return SyncResult.empty();
    }

    /**
     * 同步单个项目的工资考勤统计（由 Pipeline 调用）
     * 采集当年 + 去年两年的月度数据
     */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String account = project.getAccount();
        String password = project.getPassword();

        int currentYear = LocalDate.now().getYear();
        String[] years = {String.valueOf(currentYear), String.valueOf(currentYear - 1)};

        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;

        for (String year : years) {
            try {
                JSONObject data = apiClient.getSalaryAndAttendStats(projectNum, year, account, password);
                if (data == null) {
                    log.warn("项目【{}】年份【{}】工资考勤统计返回空数据", projectNum, year);
                    continue;
                }

                JSONObject listWrapper = data.getJSONObject("list");
                if (listWrapper == null) {
                    continue;
                }

                JSONArray monthlyList = listWrapper.getJSONArray("list");
                if (monthlyList == null || monthlyList.isEmpty()) {
                    log.info("项目【{}】年份【{}】无月度统计数据", projectNum, year);
                    continue;
                }

                String projectName = null;
                for (int i = 0; i < monthlyList.size(); i++) {
                    JSONObject item = monthlyList.getJSONObject(i);
                    totalCount++;

                    if (projectName == null) {
                        projectName = item.getStr("projectName");
                    }

                    String whichMonth = item.getStr("whichMonth");

                    try {
                        DiProjectMonthlySalaryAttendanceStats existing = statsService.lambdaQuery()
                                .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, projectNum)
                                .eq(DiProjectMonthlySalaryAttendanceStats::getWhichYear, year)
                                .eq(DiProjectMonthlySalaryAttendanceStats::getWhichMonth, whichMonth)
                                .one();

                        DiProjectMonthlySalaryAttendanceStats entity = mapToEntity(projectNum, projectName, year, whichMonth, item);

                        if (existing != null) {
                            entity.setId(existing.getId());
                            statsService.updateById(entity);
                        } else {
                            statsService.save(entity);
                        }
                        successCount++;
                    } catch (Exception e) {
                        failCount++;
                        log.error("项目【{}】月份【{}-{}】统计数据保存失败: {}", projectNum, year, whichMonth, e.getMessage(), e);
                    }
                }

                log.info("项目【{}】年份【{}】工资考勤统计同步完成，共{}条", projectNum, year, monthlyList.size());
            } catch (Exception e) {
                log.error("项目【{}】年份【{}】工资考勤统计同步失败: {}", projectNum, year, e.getMessage(), e);
                if (isAntiCrawlerMessage(e)) {
                    return SyncResult.antiCrawler(totalCount, successCount, failCount, 0);
                }
                failCount++;
            }
        }

        return SyncResult.of(totalCount, successCount, failCount, 0);
    }

    private DiProjectMonthlySalaryAttendanceStats mapToEntity(String projectNum, String projectName,
                                                                String year, String month, JSONObject item) {
        DiProjectMonthlySalaryAttendanceStats e = new DiProjectMonthlySalaryAttendanceStats();
        e.setSourceProjectNum(projectNum);
        e.setProjectName(projectName);
        e.setWhichYear(year);
        e.setWhichMonth(month);
        e.setPayType(item.getStr("payType"));
        e.setPayAmount(parseBigDecimal(item.getStr("payAmount")));
        e.setPayPersonNum(parseIntSafe(item.getStr("payPersonNum")));
        e.setAttendTimes(parseIntSafe(item.getStr("attendTimes")));
        e.setAttendPersonNum(parseIntSafe(item.getStr("attendPersonNum")));
        e.setSalaryAttendNum(parseBigDecimal(item.getStr("salaryAttendNum")));
        e.setAttendNoSalaryNum(parseBigDecimal(item.getStr("attendNoSalaryNum")));
        e.setSalaryNoAttendNum(parseBigDecimal(item.getStr("salaryNoAttendNum")));
        return e;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntSafe(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.contains(".") ? value.substring(0, value.indexOf('.')) : value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

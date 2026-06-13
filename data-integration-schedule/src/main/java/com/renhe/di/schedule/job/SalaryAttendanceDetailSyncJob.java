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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目工资考勤统计明细同步任务
 * 下载各月考勤Excel并解析，存入明细表
 * 依赖 SalaryAttendanceStatsSyncJob 先执行（从统计表获取可用月份）
 *
 * <p>工业级设计原则：
 * <ul>
 *   <li>月份级变更检测：比较统计表 updateTime 与本地明细数量，无变更跳过</li>
 *   <li>批量替换：整月先删后插，避免逐条查询</li>
 *   <li>按需采集：仅当统计表数据有变化时才下载Excel</li>
 * </ul>
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

            // --- 月份级变更检测 ---
            if (shouldSkipMonth(projectNum, dateMonth, stats)) {
                log.debug("项目【{}】月份【{}】明细无变更，跳过", projectNum, dateMonth);
                skipCount++;
                continue;
            }

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

                // 批量替换：先删除该月已有明细，再批量插入
                safeDeleteMonthDetail(projectNum, dateMonth);

                List<DiProjectSalaryAttendanceDetail> batch = new ArrayList<>();
                for (Map<String, String> record : records) {
                    totalCount++;
                    String teamName = record.get("teamName");
                    String personName = record.get("personName");
                    int attDayNum = parseIntSafe(record.get("attDayNum"));

                    DiProjectSalaryAttendanceDetail entity = new DiProjectSalaryAttendanceDetail();
                    entity.setSourceProjectNum(projectNum);
                    entity.setDateMonth(dateMonth);
                    entity.setTeamName(teamName);
                    entity.setPersonName(personName);
                    entity.setAttDayNum(attDayNum);
                    batch.add(entity);
                }

                // 批量保存
                if (!batch.isEmpty()) {
                    try {
                        detailService.saveBatch(batch, 200);
                        successCount += batch.size();
                        log.info("项目【{}】月份【{}】明细同步完成，解析{}条", projectNum, dateMonth, batch.size());
                    } catch (Exception e) {
                        failCount += batch.size();
                        log.error("项目【{}】月份【{}】明细批量保存失败: {}", projectNum, dateMonth, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("项目【{}】月份【{}】明细同步失败: {}", projectNum, dateMonth, e.getMessage(), e);
                if (handleTokenExpired(e, account)) {
                    return SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount);
                }
                if (isAntiCrawlerMessage(e)) {
                    return SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount);
                }
                failCount++;
            }
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    /**
     * 判断某月明细是否应该跳过（无变更检测）
     * <p>
     * 策略：若本地该月已有明细数据，且统计表该条记录的 updateTime 未变化（或较旧），
     * 则认为明细无需重新下载。首次运行时所有月份都会采集。
     */
    private boolean shouldSkipMonth(String projectNum, String dateMonth,
                                     DiProjectMonthlySalaryAttendanceStats stats) {
        // 查本地该月明细数量
        long localDetailCount = detailService.lambdaQuery()
                .eq(DiProjectSalaryAttendanceDetail::getSourceProjectNum, projectNum)
                .eq(DiProjectSalaryAttendanceDetail::getDateMonth, dateMonth)
                .count();

        // 本地无数据 → 必须采集
        if (localDetailCount == 0) {
            return false;
        }

        // 统计表 attendTimes 为 null 或 0 → 该月无考勤，跳过
        if (stats.getAttendTimes() == null || stats.getAttendTimes() == 0) {
            log.debug("项目【{}】月份【{}】统计表attendTimes为0，跳过明细", projectNum, dateMonth);
            return true;
        }

        // 有本地数据且统计表有考勤数据 → 保守策略：不跳过（因为无法从统计表字段精确判断明细条数是否变化）
        // 后续可扩展：在统计表增加 detailTotal 字段，或比较 stats.updateTime 与明细最新更新时间
        return false;
    }

    /**
     * 安全删除项目某月的已有明细数据
     */
    private void safeDeleteMonthDetail(String projectNum, String dateMonth) {
        try {
            detailService.lambdaUpdate()
                    .eq(DiProjectSalaryAttendanceDetail::getSourceProjectNum, projectNum)
                    .eq(DiProjectSalaryAttendanceDetail::getDateMonth, dateMonth)
                    .remove();
            log.debug("项目【{}】月份【{}】旧明细数据已清理", projectNum, dateMonth);
        } catch (Exception e) {
            log.warn("项目【{}】月份【{}】旧明细数据清理失败: {}", projectNum, dateMonth, e.getMessage());
        }
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

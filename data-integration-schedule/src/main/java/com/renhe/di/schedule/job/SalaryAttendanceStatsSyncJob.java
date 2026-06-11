package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiProjectMonthlySalaryAttendanceStats;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.service.DiProjectMonthlySalaryAttendanceStatsService;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目月份工资考勤统计同步任务
 * 调用 getSalaryAndAttendStats 接口，按年采集各月工资考勤交叉统计
 *
 * <p>工业级设计原则：
 * <ul>
 *   <li>年份回溯：从今年开始逐年往前推，直到某年返回无数据为止</li>
 *   <li>全局探针：今年先探针，与快照比对，无变更直接跳过</li>
 *   <li>批量替换：整年数据先删后插，减少逐条查询</li>
 *   <li>风控熔断：任一触发风控立即终止</li>
 * </ul>
 */
@Slf4j
@Component
public class SalaryAttendanceStatsSyncJob extends AbstractSyncJob {

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private DiProjectMonthlySalaryAttendanceStatsService statsService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    @Autowired
    private RateLimitStrategy rateLimitStrategy;

    /** 年份采集绝对下限 */
    private static final int MIN_YEAR = 2015;

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
     * 从今年开始逐年回溯，直到某年无数据为止
     */
    public SyncResult syncSingleProject(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String account = project.getAccount();
        String password = project.getPassword();
        int currentYear = LocalDate.now().getYear();

        // ===== Phase 1: 今年探针（1次轻量API调用）=====
        int currentYearTotal = probeYearTotal(projectNum, String.valueOf(currentYear), account, password);
        if (currentYearTotal < 0) {
            return SyncResult.antiCrawler(0, 0, 0, 0);
        }
        if (currentYearTotal == 0) {
            log.info("项目【{}】今年无工资考勤统计数据，跳过", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // ===== Phase 2: 全局变更检测 =====
        long localTotal = statsService.lambdaQuery()
                .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, projectNum)
                .count();
        DiSyncSnapshot globalSnap = syncSnapshotService.getLatestSnapshot(projectNum, "SALARY_ATTENDANCE_STATS");

        Integer snapEarliestYear = null;
        if (globalSnap != null && globalSnap.getRemark() != null) {
            String[] bounds = globalSnap.getRemark().split(":");
            if (bounds.length == 2) {
                try {
                    snapEarliestYear = Integer.parseInt(bounds[0]);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        boolean noChange = globalSnap != null
                && currentYearTotal == globalSnap.getThirdPartyTotal()
                && localTotal >= globalSnap.getLocalTotal();

        if (noChange) {
            // 当年无变更，只需探针边界年的前一年看是否有新历史数据
            int probeOlderYear = (snapEarliestYear != null ? snapEarliestYear : currentYear) - 1;
            if (probeOlderYear >= MIN_YEAR) {
                int olderTotal = probeYearTotal(projectNum, String.valueOf(probeOlderYear), account, password);
                if (olderTotal < 0) {
                    return SyncResult.antiCrawler(0, 0, 0, 0);
                }
                if (olderTotal == 0) {
                    log.info("项目【{}】当年无变更，更早年份{}也无数据，跳过", projectNum, probeOlderYear);
                    return SyncResult.of(0, 0, 0, 0);
                }
                log.info("项目【{}】当年无变更，但更早年份{}有数据({})，需要回溯", projectNum, probeOlderYear, olderTotal);
            } else {
                log.info("项目【{}】当年无变更，已是最早年份，跳过", projectNum);
                return SyncResult.of(0, 0, 0, 0);
            }
        }

        log.info("项目【{}】检测到变更（第三方今年total={}，本地={}），开始逐年回溯采集",
                projectNum, currentYearTotal, localTotal);

        // ===== Phase 3: 逐年回溯采集 =====
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        int actualEarliestYear = currentYear;
        boolean foundAnyData = false;

        for (int year = currentYear; year >= MIN_YEAR; year--) {
            String yearStr = String.valueOf(year);
            try {
                JSONObject data = rateLimitStrategy.executeWithRetry(
                        () -> apiClient.getSalaryAndAttendStats(projectNum, yearStr, account, password),
                        "SALARY_ATTENDANCE_STATS", projectNum, year, account);

                if (data == null) {
                    log.warn("项目【{}】年份【{}】工资考勤统计返回空数据", projectNum, yearStr);
                    // 当年份超过已知的最早有数据年份后，遇到空数据可终止
                    if (year < currentYear && (snapEarliestYear == null || year < snapEarliestYear)) {
                        log.info("项目【{}】年份【{}】无数据且已越过历史边界，终止回溯", projectNum, yearStr);
                        break;
                    }
                    continue;
                }

                JSONObject listWrapper = data.getJSONObject("list");
                if (listWrapper == null) {
                    if (year < currentYear && (snapEarliestYear == null || year < snapEarliestYear)) {
                        log.info("项目【{}】年份【{}】无list且已越过历史边界，终止回溯", projectNum, yearStr);
                        break;
                    }
                    continue;
                }

                JSONArray monthlyList = listWrapper.getJSONArray("list");
                if (monthlyList == null || monthlyList.isEmpty()) {
                    log.info("项目【{}】年份【{}】无月度统计数据", projectNum, yearStr);
                    if (year < currentYear && (snapEarliestYear == null || year < snapEarliestYear)) {
                        log.info("项目【{}】年份【{}】monthlyList为空且已越过历史边界，终止回溯", projectNum, yearStr);
                        break;
                    }
                    continue;
                }

                // 有数据，更新实际最早年份
                actualEarliestYear = year;
                foundAnyData = true;

                // 批量替换：先删除该年已有数据，再批量插入
                safeDeleteYearData(projectNum, yearStr);

                String projectName = null;
                List<DiProjectMonthlySalaryAttendanceStats> yearBatch = new ArrayList<>();

                for (int i = 0; i < monthlyList.size(); i++) {
                    JSONObject item = monthlyList.getJSONObject(i);
                    totalCount++;

                    if (projectName == null) {
                        projectName = item.getStr("projectName");
                    }
                    String whichMonth = item.getStr("whichMonth");

                    try {
                        DiProjectMonthlySalaryAttendanceStats entity =
                                mapToEntity(projectNum, projectName, yearStr, whichMonth, item);
                        yearBatch.add(entity);
                        successCount++;
                    } catch (Exception e) {
                        failCount++;
                        log.error("项目【{}】月份【{}-{}】数据映射失败: {}",
                                projectNum, yearStr, whichMonth, e.getMessage());
                    }
                }

                // 批量保存
                if (!yearBatch.isEmpty()) {
                    try {
                        statsService.saveBatch(yearBatch, 100);
                        log.info("项目【{}】年份【{}】工资考勤统计同步完成，共{}条",
                                projectNum, yearStr, yearBatch.size());
                    } catch (Exception e) {
                        log.error("项目【{}】年份【{}】批量保存失败: {}", projectNum, yearStr, e.getMessage());
                        failCount += yearBatch.size();
                        successCount -= yearBatch.size();
                    }
                }

            } catch (Exception e) {
                log.error("项目【{}】年份【{}】工资考勤统计同步失败: {}", projectNum, yearStr, e.getMessage(), e);
                if (isAntiCrawlerMessage(e)) {
                    return SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount);
                }
                failCount++;
                // 遇到非风控异常，继续尝试下一年（可能是该年数据权限问题）
            }
        }

        // ===== Phase 4: 保存全局快照 =====
        if (foundAnyData) {
            try {
                long finalLocalTotal = statsService.lambdaQuery()
                        .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, projectNum)
                        .count();
                String remark = actualEarliestYear + ":" + currentYear;
                syncSnapshotService.saveSnapshot(
                        projectNum, "SALARY_ATTENDANCE_STATS", currentYearTotal, (int) finalLocalTotal);
                // 更新remark记录年份边界
                DiSyncSnapshot updatedSnap = syncSnapshotService.getLatestSnapshot(projectNum, "SALARY_ATTENDANCE_STATS");
                if (updatedSnap != null) {
                    updatedSnap.setRemark(remark);
                    syncSnapshotService.updateById(updatedSnap);
                }
                log.info("项目【{}】工资考勤统计快照保存：第三方total={}，本地total={}，年份范围={}",
                        projectNum, currentYearTotal, finalLocalTotal, remark);
            } catch (Exception e) {
                log.error("项目【{}】工资考勤统计快照保存失败: {}", projectNum, e.getMessage());
            }
        }

        return SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    /**
     * 年份探针：获取该年 total（list.total）
     *
     * @return >=0 为总量，-1 表示风控
     */
    private int probeYearTotal(String projectNum, String year, String account, String password) {
        try {
            JSONObject probe = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getSalaryAndAttendStats(projectNum, year, account, password),
                    "SALARY_ATTENDANCE_STATS", projectNum, Integer.parseInt(year), account);
            if (probe != null) {
                JSONObject listWrapper = probe.getJSONObject("list");
                if (listWrapper != null) {
                    return listWrapper.getInt("total", 0);
                }
            }
            return 0;
        } catch (Exception e) {
            log.error("项目【{}】年份【{}】探针失败: {}", projectNum, year, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return -1;
            }
            return 0;
        }
    }

    /**
     * 安全删除项目某年的已有统计数据
     */
    private void safeDeleteYearData(String projectNum, String year) {
        try {
            statsService.lambdaUpdate()
                    .eq(DiProjectMonthlySalaryAttendanceStats::getSourceProjectNum, projectNum)
                    .eq(DiProjectMonthlySalaryAttendanceStats::getWhichYear, year)
                    .remove();
            log.debug("项目【{}】年份【{}】旧统计数据已清理", projectNum, year);
        } catch (Exception e) {
            log.warn("项目【{}】年份【{}】旧统计数据清理失败: {}", projectNum, year, e.getMessage());
        }
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

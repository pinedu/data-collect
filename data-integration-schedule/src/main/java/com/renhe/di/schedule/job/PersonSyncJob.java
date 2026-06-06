package com.renhe.di.schedule.job;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.collector.PersonCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.schedule.service.ProjectSyncExecutor;
import com.renhe.di.store.entity.DiPerson;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.entity.DiSyncSnapshot;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiPersonService;
import com.renhe.di.store.service.SyncSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 人员信息同步任务（全量）- 一次全量拉取 + 本地月份拆分
 * <p>
 * Phase A - 全量探针：第1页获取total + 最后一页获取最早beginDate
 * Phase B - 月份划分
 * Phase C - 一次全量拉取（API忽略时间过滤，分月拉取重复浪费）→ 按beginDate归属月份分组
 * Phase D - 逐月处理：快照比对（已同步跳过）→ 清洗入库 → 保存月快照
 * Phase E - sourceProjectNum统计一致性校验
 */
@Slf4j
@Component
public class PersonSyncJob extends AbstractSyncJob {

    @Autowired
    private PersonCollector personCollector;

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private DiPersonService personService;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private ProjectSyncExecutor projectSyncExecutor;

    @Autowired
    private BatchInsertService batchInsertService;

    @Autowired
    private SyncSnapshotService syncSnapshotService;

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private ExecutorService syncTaskExecutor;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 支持手动触发或独立调度人员信息同步
     * 默认不参与定时调度，由 ProjectDataSyncPipeline 统一编排
     */
    public void scheduledExecute() throws Exception {
        super.execute();
    }

    @Override
    protected String getDataType() {
        return "PERSON";
    }

    @Override
    protected String getSyncType() {
        return "FULL";
    }

    @Override
    protected String getTaskName() {
        return "人员信息全量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 100, this::syncSingleProject);
    }

    public SyncResult syncSingleProject(DiProjectConfig project) {
        return syncSingleProject(project.getSourceProjectNum(), project.getAccount(), project.getPassword());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A: 全量探针 =====
        // Step 1: 查询第1页（无时间过滤）获取第三方人员总量
        int thirdPartyTotal = 0;
        try {
            int callSeq = PersonCollector.API_CALL_COUNTER.incrementAndGet();
            log.info("[PERSON API #{:03d}] PhaseA探针 page=1, pageSize=1, project={}, beginDate=null, endDate=null",
                    callSeq, projectNum);

            JSONObject probeData = apiClient.getPersonPage(projectNum, 1, 1, null, null, account, password);
            if (probeData != null) {
                thirdPartyTotal = probeData.getInt("total", 0);
            }
            log.info("[PERSON API #{:03d}] 返回 total={}", callSeq, thirdPartyTotal);
            log.info("项目【{}】第三方人员全量总量：{}", projectNum, thirdPartyTotal);
        } catch (Exception e) {
            log.error("项目【{}】人员全量探针获取总量失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】人员探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (thirdPartyTotal == 0) {
            log.info("项目【{}】第三方无人员数据，跳过同步", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // Step 2: 计算最后一页并查询，取最旧的 beginDate 作为 fullStartDate
        int lastPageNum = (int) Math.ceil((double) thirdPartyTotal / 100);
        LocalDate fullStartDate = null;
        try {
            int callSeq = PersonCollector.API_CALL_COUNTER.incrementAndGet();
            log.info("[PERSON API #{:03d}] PhaseA末页 page={}, pageSize=100, project={}, beginDate=null, endDate=null",
                    callSeq, lastPageNum, projectNum);

            JSONObject lastPageData = apiClient.getPersonPage(projectNum, lastPageNum, 100,
                    null, null, account, password);
            int lastPageRows = 0;
            if (lastPageData != null) {
                JSONArray lastPageList = lastPageData.getJSONArray("list");
                if (lastPageList != null) {
                    lastPageRows = lastPageList.size();
                    for (int i = 0; i < lastPageList.size(); i++) {
                        JSONObject record = lastPageList.getJSONObject(i);
                        String beginDate = record.getStr("beginDate");
                        if (beginDate != null && !beginDate.isEmpty()) {
                            try {
                                LocalDate bd = LocalDate.parse(beginDate, DATE_FMT);
                                if (fullStartDate == null || bd.isBefore(fullStartDate)) {
                                    fullStartDate = bd;
                                }
                            } catch (Exception e) {
                                log.warn("人员登记时间解析失败: {}", beginDate);
                            }
                        }
                    }
                }
            }
            log.info("[PERSON API #{:03d}] 返回 {} 条", callSeq, lastPageRows);
        } catch (Exception e) {
            log.error("项目【{}】获取最后一页人员数据失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                log.warn("项目【{}】人员最后一页探针触发风控，立即终止", projectNum);
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (fullStartDate == null) {
            fullStartDate = LocalDate.of(2020, 1, 1);
            log.warn("项目【{}】最后一页未获取到登记时间，使用默认起始时间：{}", projectNum, fullStartDate);
        } else {
            log.info("项目【{}】从最后一页获取到最早登记时间作为同步起点：{}", projectNum, fullStartDate);
        }

        // ===== Phase B: 月份划分 + 快照比对 =====
        YearMonth currentMonth = YearMonth.from(fullStartDate);
        YearMonth endMonth = YearMonth.now();
        int monthCount = 0;
        int totalMonths = (int) java.time.temporal.ChronoUnit.MONTHS.between(currentMonth, endMonth) + 1;

        Set<String> processedIds = new HashSet<>();

        // ===== Phase C: 快照完整性检查——全部完成则直接跳过全量拉取 =====
        boolean allMonthsComplete = true;
        YearMonth checkMonth = YearMonth.from(fullStartDate);
        while (!checkMonth.isAfter(endMonth)) {
            try {
                DiSyncSnapshot snap = syncSnapshotService.getMonthSnapshot(projectNum, "PERSON", checkMonth.toString());
                if (snap == null || snap.getMonthThirdPartyTotal() == null || snap.getMonthThirdPartyTotal() <= 0) {
                    allMonthsComplete = false;
                    break;
                }
                long localCount = personService.lambdaQuery()
                        .eq(DiPerson::getSourceProjectNum, projectNum)
                        .eq(DiPerson::getDeleted, 0)
                        .ge(DiPerson::getRegisterTime, checkMonth.atDay(1))
                        .le(DiPerson::getRegisterTime, checkMonth.atEndOfMonth())
                        .count();
                if (localCount < snap.getMonthThirdPartyTotal()) {
                    allMonthsComplete = false;
                    break;
                }
            } catch (Exception e) {
                allMonthsComplete = false;
                break;
            }
            checkMonth = checkMonth.plusMonths(1);
        }

        Map<String, List<JSONObject>> monthGroups = new LinkedHashMap<>();

        if (allMonthsComplete) {
            log.info("项目【{}】所有{}个月份均已完全同步，跳过全量拉取", projectNum, totalMonths);
        } else {
            // ===== Phase C: 一次全量拉取（API 忽略时间过滤，分月拉取重复浪费） =====
            log.info("项目【{}】开始一次性全量采集{}个人员数据（含{}个月份）",
                    projectNum, thirdPartyTotal, totalMonths);

            try {
                CollectContext fullCtx = CollectContext.builder()
                        .sourceProjectNum(projectNum)
                        .account(account)
                        .password(password)
                        .build();

                List<JSONObject> allRawList = personCollector.collectAllConcurrent(fullCtx, syncTaskExecutor);

                if (Boolean.TRUE.equals(fullCtx.getExtraParam("_antiCrawlerTriggered"))) {
                    log.error("项目【{}】全量人员采集触发风控，已采集{}条部分数据，继续处理已完整月份",
                            projectNum, allRawList != null ? allRawList.size() : 0);
                    antiCrawlerTriggered = true;
                }

                // 按 beginDate 归属月份分组
                if (allRawList != null) {
                    for (JSONObject raw : allRawList) {
                        String beginDateStr = raw.getStr("beginDate");
                        if (beginDateStr != null && !beginDateStr.isEmpty()) {
                            try {
                                LocalDate bd = LocalDate.parse(beginDateStr, DATE_FMT);
                                String monthKey = YearMonth.from(bd).toString();
                                monthGroups.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(raw);
                            } catch (Exception e) {
                                log.warn("人员登记时间无法解析归属月份: {}", beginDateStr);
                            }
                        }
                    }
                    log.info("项目【{}】全量{}条人员数据按月份分组完成，共{}个月",
                            projectNum, allRawList.size(), monthGroups.size());
                }
            } catch (Exception e) {
                log.error("项目【{}】全量人员采集失败: {}", projectNum, e.getMessage());
                return SyncResult.of(0, 0, 1, 0);
            }
        }

        // ===== Phase D: 逐月处理（清洗+入库+快照） =====
        if (!allMonthsComplete) {
        while (!currentMonth.isAfter(endMonth)) {
            monthCount++;
            final String monthId = currentMonth.toString();

            // 快照比对：已完全同步则跳过
            try {
                DiSyncSnapshot monthSnapshot =
                        syncSnapshotService.getMonthSnapshot(projectNum, "PERSON", monthId);
                if (monthSnapshot != null && monthSnapshot.getMonthSyncDate() != null
                        && monthSnapshot.getMonthThirdPartyTotal() != null
                        && monthSnapshot.getMonthThirdPartyTotal() > 0) {
                    long localMonthCount = personService.lambdaQuery()
                            .eq(DiPerson::getSourceProjectNum, projectNum)
                            .eq(DiPerson::getDeleted, 0)
                            .ge(DiPerson::getRegisterTime, currentMonth.atDay(1))
                            .le(DiPerson::getRegisterTime, currentMonth.atEndOfMonth())
                            .count();
                    if (localMonthCount >= monthSnapshot.getMonthThirdPartyTotal()) {
                        log.info("项目【{}】月份【{}】已完全同步（本地{}>=第三方{}），跳过",
                                projectNum, monthId, localMonthCount, monthSnapshot.getMonthThirdPartyTotal());
                        currentMonth = currentMonth.plusMonths(1);
                        continue;
                    }
                }
            } catch (Exception e) {
                log.warn("项目【{}】查询月份【{}】快照失败: {}", projectNum, monthId, e.getMessage());
            }

            List<JSONObject> monthRawList = monthGroups.getOrDefault(monthId, new ArrayList<>());
            if (monthRawList.isEmpty()) {
                log.info("项目【{}】月份【{}】第三方无数据", projectNum, monthId);
                currentMonth = currentMonth.plusMonths(1);
                continue;
            }

            log.info("项目【{}】处理第{}个月【{}】：{}条数据",
                    projectNum, monthCount, monthId, monthRawList.size());

            LocalDate minBeginDate = null;
            int monthThirdPartyTotal = monthRawList.size();

            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("PERSON")
                    .build();

            List<DiPerson> personBatch = new ArrayList<>();
            for (JSONObject raw : monthRawList) {
                totalCount++;
                String dataId = raw.getStr("id");

                if (processedIds.contains(dataId)) {
                    skipCount++;
                    continue;
                }

                // 追踪最小 beginDate 作为 monthSyncDate
                String beginDateStr = raw.getStr("beginDate");
                if (beginDateStr != null && !beginDateStr.isEmpty()) {
                    try {
                        LocalDate bd = LocalDate.parse(beginDateStr, DATE_FMT);
                        if (minBeginDate == null || bd.isBefore(minBeginDate)) {
                            minBeginDate = bd;
                        }
                    } catch (Exception e) {
                        log.warn("登记时间解析失败: {}", beginDateStr);
                    }
                }

                try {
                    DiPerson person = cleanPipeline.execute(raw, cleanCtx, "PERSON");
                    if (person != null) {
                        personBatch.add(person);
                        processedIds.add(dataId);
                    } else {
                        skipCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("人员数据清洗失败: {}", dataId, e);
                }
            }

            if (!personBatch.isEmpty()) {
                int batchSuccess = batchInsertService.batchInsertOrUpdate(personBatch, personService, 500);
                successCount += batchSuccess;
                failCount += (personBatch.size() - batchSuccess);

                for (DiPerson person : personBatch) {
                    dataChangePublisher.publish("PERSON", projectNum, person.getPersonId(), "CREATE");
                }
            }

            // 保存月份快照
            if (minBeginDate != null && monthThirdPartyTotal > 0) {
                try {
                    syncSnapshotService.saveMonthSnapshot(
                            projectNum, "PERSON", monthId,
                            minBeginDate.atStartOfDay(), monthThirdPartyTotal);
                    log.info("项目【{}】月份【{}】人员同步进度已保存：monthSyncDate={}, thirdTotal={}",
                            projectNum, monthId, minBeginDate, monthThirdPartyTotal);
                } catch (Exception e) {
                    log.error("项目【{}】月份【{}】快照保存失败: {}", projectNum, monthId, e.getMessage());
                }
            }

            currentMonth = currentMonth.plusMonths(1);
        }
        } // allMonthsComplete

        log.info("项目【{}】人员全量同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        // ===== Phase E: 一致性校验（按来源统计） =====
        try {
            long localTotal = personService.lambdaQuery()
                    .eq(DiPerson::getSourceProjectNum, projectNum)
                    .eq(DiPerson::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(projectNum, "PERSON", thirdPartyTotal, (int) localTotal);
        } catch (Exception e) {
            log.error("项目【{}】人员同步快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }
}

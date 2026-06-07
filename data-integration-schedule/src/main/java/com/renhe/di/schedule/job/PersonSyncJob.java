package com.renhe.di.schedule.job;

import cn.hutool.json.JSONObject;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.collector.PersonCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import com.renhe.di.collect.strategy.RateLimitStrategy;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 人员信息同步任务（智能增量）- 按月份时间范围拉取 + 快照比对跳过 + 变更检测提前终止
 * <p>
 * Phase A - 变更探测：1次探针获取全局total，与本地+全局快照比对，无变更直接结束
 * Phase B - 逆序月份扫描：从当前月 → 最旧月，逐月按需拉取（传时间范围），连续N月快照匹配则提前终止
 * Phase C - 逐月清洗入库：清洗 → 批量入库 → 事件发布 → 保存月快照
 * Phase D - 一致性校验：第三方总量 vs 本地总量
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
    private RateLimitStrategy rateLimitStrategy;

    @Autowired
    private ExecutorService syncTaskExecutor;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 连续快照匹配月份数达到此阈值时，终止逆序扫描 */
    private static final int STOP_THRESHOLD = 3;

    /** 无快照时的默认起始月份 */
    private static final YearMonth DEFAULT_EARLIEST_MONTH = YearMonth.of(2020, 1);

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
        return "INCREMENTAL";
    }

    @Override
    protected String getTaskName() {
        return "人员信息增量同步";
    }

    @Override
    protected SyncResult doSync(String projectNum) {
        return projectSyncExecutor.execute(projectNum, 100, this::syncSingleProject);
    }

    public SyncResult syncSingleProject(DiProjectConfig project) {
        YearMonth earliestMonth = project.getActualBeginDate() != null
                ? YearMonth.from(project.getActualBeginDate())
                : DEFAULT_EARLIEST_MONTH;
        return syncSingleProject(
                project.getSourceProjectNum(), project.getAccount(), project.getPassword(), earliestMonth);
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password,
                                          YearMonth earliestMonth) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        boolean antiCrawlerTriggered = false;

        // ===== Phase A: 变更探测（1次轻量API调用）=====
        int thirdPartyTotal = 0;
        try {
            int callSeq = PersonCollector.API_CALL_COUNTER.incrementAndGet();
            log.info("[PERSON API #{}] PhaseA探针 page=1, pageSize=1, project={}, 无时间过滤",
                    callSeq, projectNum);

            JSONObject probeData = apiClient.getPersonPage(projectNum, 1, 1, null, null, account, password);
            if (probeData != null) {
                thirdPartyTotal = probeData.getInt("total", 0);
            }
            log.info("[PERSON API #{}] 返回 total={}", callSeq, thirdPartyTotal);
        } catch (Exception e) {
            log.error("项目【{}】人员探针失败: {}", projectNum, e.getMessage());
            if (isAntiCrawlerMessage(e)) {
                return SyncResult.antiCrawler(0, 0, 0, 0);
            }
        }

        if (thirdPartyTotal == 0) {
            log.info("项目【{}】第三方无人员数据，跳过同步", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // 全局变更检测：总量 + 本地数量 + 上次快照 三者一致 → 无变更
        long localTotal = personService.lambdaQuery()
                .eq(DiPerson::getSourceProjectNum, projectNum)
                .eq(DiPerson::getDeleted, 0)
                .count();
        DiSyncSnapshot globalSnap = syncSnapshotService.getLatestSnapshot(projectNum, "PERSON");

        if (globalSnap != null
                && thirdPartyTotal == globalSnap.getThirdPartyTotal()
                && localTotal >= globalSnap.getLocalTotal()) {
            log.info("项目【{}】全局无变更（第三方={}，本地={}，快照匹配），跳过同步",
                    projectNum, thirdPartyTotal, localTotal);
            return SyncResult.of(0, 0, 0, 0);
        }

        log.info("项目【{}】检测到变更（第三方={}，本地={}），开始增量同步，扫描范围【{}~{}】",
                projectNum, thirdPartyTotal, localTotal, earliestMonth, YearMonth.now());

        // ===== Phase B: 逆序月份扫描 + 按需拉取 =====
        YearMonth scanMonth = YearMonth.now();
        int consecutiveSkips = 0;
        Set<String> processedIds = new HashSet<>();

        while (!scanMonth.isBefore(earliestMonth)) {
            final String monthId = scanMonth.toString();

            // Step 1: 轻量探针（pageSize=1，仅获取该月 total）
            int monthTotal = probeMonthTotal(projectNum, scanMonth, account, password);
            if (monthTotal < 0) {
                // 探针异常（可能触发风控）
                antiCrawlerTriggered = true;
                log.warn("项目【{}】月份【{}】探针异常，终止扫描", projectNum, monthId);
                break;
            }

            // Step 2: 快照比对
            DiSyncSnapshot snap = syncSnapshotService.getMonthSnapshot(projectNum, "PERSON", monthId);
            long localMonthCount = countLocalPersons(projectNum, scanMonth);

            if (snap != null && snap.getMonthThirdPartyTotal() != null
                    && monthTotal == snap.getMonthThirdPartyTotal()
                    && localMonthCount >= snap.getMonthThirdPartyTotal()) {
                consecutiveSkips++;
                log.info("项目【{}】月份【{}】快照匹配（第三方={}，本地={}），跳过 [{}/{}]",
                        projectNum, monthId, monthTotal, localMonthCount,
                        consecutiveSkips, STOP_THRESHOLD);
                if (consecutiveSkips >= STOP_THRESHOLD) {
                    log.info("项目【{}】连续{}个月快照匹配，终止扫描（更早月份视为无变更）",
                            projectNum, consecutiveSkips);
                    break;
                }
                scanMonth = scanMonth.minusMonths(1);
                continue;
            }

            consecutiveSkips = 0; // 有变更，重置计数

            // Step 3: 该月有变更或从未同步，按需拉取
            if (monthTotal > 0) {
                log.info("项目【{}】月份【{}】需要同步（第三方={}，本地={}，快照={}）",
                        projectNum, monthId, monthTotal, localMonthCount,
                        snap != null ? snap.getMonthThirdPartyTotal() : "无");

                List<JSONObject> monthData = fetchMonthData(projectNum, scanMonth, account, password);

                if (monthData != null && !monthData.isEmpty()) {
                    int[] result = processMonthData(projectNum, scanMonth, monthId, monthData, processedIds);
                    totalCount += result[0];
                    successCount += result[1];
                    failCount += result[2];
                    skipCount += result[3];
                    if (result[4] == 1) {
                        antiCrawlerTriggered = true;
                        log.warn("项目【{}】月份【{}】拉取触发风控，终止扫描", projectNum, monthId);
                        break;
                    }
                }
            } else {
                log.info("项目【{}】月份【{}】第三方无数据", projectNum, monthId);
            }

            scanMonth = scanMonth.minusMonths(1);
        }

        log.info("项目【{}】人员增量同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        // ===== Phase D: 一致性校验 =====
        try {
            long finalLocalTotal = personService.lambdaQuery()
                    .eq(DiPerson::getSourceProjectNum, projectNum)
                    .eq(DiPerson::getDeleted, 0)
                    .count();
            syncSnapshotService.saveSnapshot(projectNum, "PERSON", thirdPartyTotal, (int) finalLocalTotal);
        } catch (Exception e) {
            log.error("项目【{}】人员同步快照保存失败: {}", projectNum, e.getMessage());
        }

        return antiCrawlerTriggered
                ? SyncResult.antiCrawler(totalCount, successCount, failCount, skipCount)
                : SyncResult.of(totalCount, successCount, failCount, skipCount);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 轻量探针：获取指定月份的数据总量（pageSize=1，不拉取实际数据）
     *
     * @return 该月第三方数据总量，-1 表示请求失败
     */
    private int probeMonthTotal(String projectNum, YearMonth month, String account, String password) {
        try {
            int callSeq = PersonCollector.API_CALL_COUNTER.incrementAndGet();
            String beginDate = month.atDay(1).format(DATE_FMT);
            String endDate = month.atEndOfMonth().format(DATE_FMT);
            log.info("[PERSON API #{}] 月份探针 page=1, pageSize=1, project={}, month={}",
                    callSeq, projectNum, month);

            JSONObject data = apiClient.getPersonPage(projectNum, 1, 1,
                    beginDate, endDate, account, password);
            return data != null ? data.getInt("total", 0) : 0;
        } catch (Exception e) {
            if (isAntiCrawlerMessage(e)) {
                log.error("项目【{}】月份【{}】探针触发风控", projectNum, month);
            } else {
                log.warn("项目【{}】月份【{}】探针失败: {}", projectNum, month, e.getMessage());
            }
            return -1;
        }
    }

    /**
     * 拉取指定月份的全部人员数据（简单逐页循环，pageSize=100）
     *
     * <p>用响应自身的 total 决定是否翻页：
     * <ul>
     *   <li>大多数月份 &lt; 100 条 → 一次请求即到下一个月</li>
     *   <li>total &gt; 100 时继续下一页</li>
     *   <li>某页返回 &lt; pageSize 条 → 最后一页，停止</li>
     * </ul>
     */
    private List<JSONObject> fetchMonthData(String projectNum, YearMonth month,
                                             String account, String password) {
        List<JSONObject> allData = new ArrayList<>();
        int pageSize = 100;
        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .beginTime(month.atDay(1).atStartOfDay())
                .endTime(month.atEndOfMonth().atTime(23, 59, 59))
                .build();

        try {
            for (int page = 1; ; page++) {
                final int currentPage = page;
                PageResult<JSONObject> pageResult = rateLimitStrategy.executeWithRetry(
                        () -> personCollector.collectPage(ctx, currentPage, pageSize),
                        "PERSON", projectNum, currentPage, account);

                if (pageResult == null || pageResult.getList() == null || pageResult.getList().isEmpty()) {
                    log.info("项目【{}】月份【{}】第{}页无数据（pageSize={}），月份采集结束", projectNum, month, page, pageSize);
                    break;
                }

                allData.addAll(pageResult.getList());
                int total = pageResult.getTotal();

                log.info("项目【{}】月份【{}】第{}页完成，本页{}条/{}，响应total={}，累计{}条",
                        projectNum, month, page, pageResult.getList().size(), pageSize, total, allData.size());

                // 某页返回 < pageSize 条 → 最后一页
                if (pageResult.getList().size() < pageSize) {
                    break;
                }

                // 响应 total 已知且当前页已覆盖全部数据
                if (total > 0 && page * pageSize >= total) {
                    log.info("项目【{}】月份【{}】已采集全部{}条（total={}，pageSize={}），月份采集结束",
                            projectNum, month, allData.size(), total, pageSize);
                    break;
                }

                // 页间限流延迟
                rateLimitStrategy.applyDelay("PERSON", projectNum, page + 1, account);
            }
            return allData;
        } catch (Exception e) {
            log.error("项目【{}】月份【{}】数据拉取失败（已采集{}条）: {}",
                    projectNum, month, allData.size(), e.getMessage());
            return allData;
        }
    }

    /**
     * 处理单月数据：清洗 → 入库 → 事件发布 → 保存快照
     *
     * @return [totalCount, successCount, failCount, skipCount, antiCrawlerFlag(0/1)]
     */
    private int[] processMonthData(String projectNum, YearMonth month, String monthId,
                                    List<JSONObject> monthData, Set<String> processedIds) {
        int total = 0, success = 0, fail = 0, skip = 0;

        LocalDate minBeginDate = null;
        CleanContext cleanCtx = CleanContext.builder()
                .sourceProjectNum(projectNum)
                .dataType("PERSON")
                .build();

        List<DiPerson> personBatch = new ArrayList<>();
        for (JSONObject raw : monthData) {
            total++;
            String dataId = raw.getStr("id");

            if (processedIds.contains(dataId)) {
                skip++;
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
                    skip++;
                }
            } catch (Exception e) {
                fail++;
                log.error("人员数据清洗失败: {}", dataId, e);
            }
        }

        // 批量入库
        if (!personBatch.isEmpty()) {
            int batchSuccess = batchInsertService.batchInsertOrUpdate(personBatch, personService, 500);
            success += batchSuccess;
            fail += (personBatch.size() - batchSuccess);

            for (DiPerson person : personBatch) {
                dataChangePublisher.publish("PERSON", projectNum, person.getPersonId(), "CREATE");
            }
        }

        // 保存月份快照
        if (minBeginDate != null && monthData.size() > 0) {
            try {
                syncSnapshotService.saveMonthSnapshot(
                        projectNum, "PERSON", monthId,
                        minBeginDate.atStartOfDay(), monthData.size());
                log.info("项目【{}】月份【{}】快照已保存：monthSyncDate={}, thirdTotal={}",
                        projectNum, monthId, minBeginDate, monthData.size());
            } catch (Exception e) {
                log.error("项目【{}】月份【{}】快照保存失败: {}", projectNum, monthId, e.getMessage());
            }
        }

        log.info("项目【{}】月份【{}】处理完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, monthId, total, success, fail, skip);

        return new int[]{total, success, fail, skip, 0};
    }

    /**
     * 统计本地指定月份的人员数量
     */
    private long countLocalPersons(String projectNum, YearMonth month) {
        return personService.lambdaQuery()
                .eq(DiPerson::getSourceProjectNum, projectNum)
                .eq(DiPerson::getDeleted, 0)
                .ge(DiPerson::getRegisterTime, month.atDay(1))
                .le(DiPerson::getRegisterTime, month.atEndOfMonth())
                .count();
    }
}

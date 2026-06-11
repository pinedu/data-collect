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

import java.util.ArrayList;
import java.util.List;

/**
 * 人员信息同步任务 - 全量分页拉取 + 快照变更检测
 * <p>
 * 第三方人员API不支持时间过滤（传beginDate/endDate仍返回全量数据），
 * 且人员数据量较小（通常 ≤ 3000条），因此采用直接全量分页拉取策略：
 * <p>
 * Phase 1 - 变更探测：1次探针获取全局total，与本地+快照比对，无变更直接结束
 * Phase 2 - 全量拉取：无时间过滤，逐页拉取全部数据（pageSize=100）
 * Phase 3 - 清洗入库：清洗 → 批量UPSERT → 事件发布
 * Phase 4 - 保存快照：保存全局快照（thirdPartyTotal + localTotal）
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
        return syncSingleProject(
                project.getSourceProjectNum(), project.getAccount(), project.getPassword());
    }

    private SyncResult syncSingleProject(String projectNum, String account, String password) {
        // ===== Phase 1: 变更探测（1次轻量API调用）=====
        int thirdPartyTotal = 0;
        try {
            int callSeq = PersonCollector.API_CALL_COUNTER.incrementAndGet();
            log.info("[PERSON API #{}] 探针 page=1, pageSize=1, project={}", callSeq, projectNum);

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

        // 全局变更检测：第三方总量 + 本地数量 + 快照 三者一致 → 无变更
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

        log.info("项目【{}】检测到变更（第三方={}，本地={}），开始全量拉取",
                projectNum, thirdPartyTotal, localTotal);

        // ===== Phase 2: 全量分页拉取（无时间过滤）=====
        List<JSONObject> allData = fetchAllData(projectNum, account, password);
        if (allData.isEmpty()) {
            log.info("项目【{}】全量拉取无数据", projectNum);
            return SyncResult.of(0, 0, 0, 0);
        }

        // ===== Phase 3: 清洗入库 =====
        int[] result = processAllData(projectNum, allData);
        int totalCount = result[0];
        int successCount = result[1];
        int failCount = result[2];
        int skipCount = result[3];
        boolean antiCrawlerTriggered = result[4] == 1;

        log.info("项目【{}】人员全量同步完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, totalCount, successCount, failCount, skipCount);

        // ===== Phase 4: 保存全局快照 =====
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
     * 全量分页拉取所有人员数据（无时间过滤，pageSize=100）
     * <p>
     * 终止条件：某页返回 &lt; pageSize 条，或 page * pageSize >= total
     */
    private List<JSONObject> fetchAllData(String projectNum, String account, String password) {
        List<JSONObject> allData = new ArrayList<>();
        int pageSize = 100;

        // 不传时间范围，API返回全量数据
        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .build();

        try {
            for (int page = 1; ; page++) {
                final int currentPage = page;
                PageResult<JSONObject> pageResult = rateLimitStrategy.executeWithRetry(
                        () -> personCollector.collectPage(ctx, currentPage, pageSize),
                        "PERSON", projectNum, currentPage, account);

                if (pageResult == null || pageResult.getList() == null || pageResult.getList().isEmpty()) {
                    log.info("项目【{}】第{}页无数据，拉取结束", projectNum, page);
                    break;
                }

                allData.addAll(pageResult.getList());
                int total = pageResult.getTotal();

                log.info("项目【{}】第{}页完成，本页{}条，响应total={}，累计{}条",
                        projectNum, page, pageResult.getList().size(), total, allData.size());

                // 最后一页
                if (pageResult.getList().size() < pageSize) {
                    break;
                }

                // 已采集全部数据
                if (total > 0 && page * pageSize >= total) {
                    log.info("项目【{}】已采集全部{}条（total={}），拉取结束",
                            projectNum, allData.size(), total);
                    break;
                }

                // 页间限流延迟
                rateLimitStrategy.applyDelay("PERSON", projectNum, page + 1, account);
            }
            return allData;
        } catch (Exception e) {
            log.error("项目【{}】全量拉取失败（已采集{}条）: {}", projectNum, allData.size(), e.getMessage());
            return allData;
        }
    }

    /**
     * 清洗全部数据 → 批量UPSERT → 事件发布
     *
     * @return [totalCount, successCount, failCount, skipCount, antiCrawlerFlag(0/1)]
     */
    private int[] processAllData(String projectNum, List<JSONObject> allData) {
        int total = 0, success = 0, fail = 0, skip = 0;

        CleanContext cleanCtx = CleanContext.builder()
                .sourceProjectNum(projectNum)
                .dataType("PERSON")
                .build();

        List<DiPerson> personBatch = new ArrayList<>();
        for (JSONObject raw : allData) {
            total++;
            String dataId = raw.getStr("id");

            try {
                DiPerson person = cleanPipeline.execute(raw, cleanCtx, "PERSON");
                if (person != null) {
                    personBatch.add(person);
                } else {
                    skip++;
                }
            } catch (Exception e) {
                fail++;
                log.error("人员数据清洗失败: {}", dataId, e);
            }
        }

        // 批量UPSERT入库
        if (!personBatch.isEmpty()) {
            int batchSuccess = batchInsertService.batchInsertOrUpdate(personBatch, personService, 200);
            success += batchSuccess;
            fail += (personBatch.size() - batchSuccess);

            for (DiPerson person : personBatch) {
                dataChangePublisher.publish("PERSON", projectNum, person.getPersonId(), "CREATE");
            }
        }

        log.info("项目【{}】数据处理完成：总计{}条，成功{}条，失败{}条，跳过{}条",
                projectNum, total, success, fail, skip);

        return new int[]{total, success, fail, skip, 0};
    }
}

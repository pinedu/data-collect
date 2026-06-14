package com.renhe.di.attendancepoll.task;

import cn.hutool.json.JSONObject;
import com.renhe.di.attendancepoll.config.AttendancePollProperties;
import com.renhe.di.attendancepoll.model.PollStatus;
import com.renhe.di.attendancepoll.store.PollProgressStore;
import com.renhe.di.clean.pipeline.CleanContext;
import com.renhe.di.clean.pipeline.CleanPipeline;
import com.renhe.di.collect.api.AntiCrawlerDetector;
import com.renhe.di.collect.api.ThirdPartyApiClient;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.collect.collector.AttendanceCollector;
import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.dispatch.publisher.DataChangePublisher;
import com.renhe.di.store.entity.DiAttendance;
import com.renhe.di.store.entity.DiProjectConfig;
import com.renhe.di.store.service.BatchInsertService;
import com.renhe.di.store.service.DiAttendanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 单项目考勤采集任务 — 全量平铺翻页模式（跨轮次断点续传）
 * <p>
 * 核心设计：
 * <ul>
 *   <li>Phase A：探针获取第三方 total（带时间范围过滤）</li>
 *   <li>Phase A2：检查本地数据量 → 若已采完则直接 COMPLETED</li>
 *   <li>Phase B：从 localCount/pageSize+1 开始翻页（跳过已入库数据）</li>
 *   <li>风控中断后 currentPage 保存到 Redis，下一轮自动续传</li>
 *   <li>UPSERT 幂等保证重复入库安全</li>
 * </ul>
 */
@Slf4j
@Component
public class AttendancePollTask {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private AttendanceCollector attendanceCollector;

    @Autowired
    private CleanPipeline cleanPipeline;

    @Autowired
    private BatchInsertService batchInsertService;

    @Autowired
    private DiAttendanceService attendanceService;

    @Autowired
    private RateLimitStrategy rateLimitStrategy;

    @Autowired
    private ThirdPartyApiClient apiClient;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private PollProgressStore progressStore;

    @Autowired
    private DataChangePublisher dataChangePublisher;

    @Autowired
    private AttendancePollProperties properties;

    /**
     * 执行单项目一轮采集（支持跨轮次断点续传）
     *
     * @return true=本轮完成（含已采完跳过），false=中途终止（风控/Token/异常）
     */
    public boolean collect(DiProjectConfig project) {
        String projectNum = project.getSourceProjectNum();
        String account = project.getAccount();
        String password = project.getPassword();
        int pageSize = properties.getPageSize();

        int round = progressStore.getRound(projectNum) + 1;
        progressStore.saveRound(projectNum, round);
        log.info("项目【{}】开始第 {} 轮考勤采集", projectNum, round);

        // ===== 确定采集时间范围 =====
        LocalDateTime endTime = YearMonth.now().minusMonths(1)
                .atEndOfMonth().atTime(23, 59, 59);
        String endTimeStr = endTime.format(DT_FMT);

        // ===== Phase A：探针 → 获取时间范围内的 total =====
        progressStore.saveStatus(projectNum, PollStatus.PROBING);
        int thirdPartyTotal = probeTotalInRange(projectNum, account, password, endTimeStr);

        if (thirdPartyTotal < 0) {
            return false; // 探针失败（Token过期或风控）
        }
        if (thirdPartyTotal == 0) {
            log.info("项目【{}】时间范围 ~{} 内无考勤数据，本轮完成", projectNum, endTime);
            progressStore.saveStatus(projectNum, PollStatus.COMPLETED);
            return true;
        }

        progressStore.saveGlobalTotal(projectNum, thirdPartyTotal);
        int totalPages = (int) Math.ceil((double) thirdPartyTotal / pageSize);
        progressStore.saveTotalPages(projectNum, totalPages);

        // ===== Phase A2：检查本地数据量 → 决定是否跳过采集 =====
        long localCount = countLocalAttendance(projectNum, endTime);
        if (localCount >= thirdPartyTotal) {
            log.info("项目【{}】本地已有{}条 >= 第三方{}条，数据已完整，跳过采集",
                    projectNum, localCount, thirdPartyTotal);
            progressStore.saveStatus(projectNum, PollStatus.COMPLETED);
            return true;
        }

        // 计算续传起始页（跳过已入库的完整页）
        int startPage = (int) (localCount / pageSize) + 1;
        if (startPage < 1) startPage = 1;

        log.info("项目【{}】续传策略：本地{}条/第三方{}条，从第{}/{}页继续",
                projectNum, localCount, thirdPartyTotal, startPage, totalPages);

        // ===== Phase A3：获取最早 clockingTime → beginTime =====
        LocalDateTime beginTime = probeEarliestClockingTime(
                projectNum, account, password, thirdPartyTotal, pageSize, endTimeStr);

        if (beginTime == null) {
            log.error("项目【{}】无法获取最早考勤时间，第三方API可能异常，终止本轮采集", projectNum);
            return false;
        }

        log.info("项目【{}】采集时间范围：{} ~ {}，剩余{}页待采集",
                projectNum, beginTime, endTime, totalPages - startPage + 1);

        // ===== Phase B：平铺翻页采集（从 startPage 开始） =====
        progressStore.saveStatus(projectNum, PollStatus.COLLECTING);

        CollectContext ctx = CollectContext.builder()
                .sourceProjectNum(projectNum)
                .account(account)
                .password(password)
                .beginTime(beginTime)
                .endTime(endTime)
                .build();

        int[] stats = {0, 0, 0}; // total, success, fail
        int[] lastPage = {startPage - 1};

        attendanceCollector.collectStreaming(ctx, startPage, (pageData, pageNum, total) -> {
            // 每页回调前检查状态
            PollStatus status = progressStore.getStatus(projectNum);
            if (status == PollStatus.TOKEN_EXPIRED) {
                log.warn("项目【{}】第{}页检测到 Token 失效，终止采集", projectNum, pageNum);
                return false;
            }
            if (status == PollStatus.RATE_LIMITED) {
                log.warn("项目【{}】第{}页检测到风控标记，终止采集", projectNum, pageNum);
                return false;
            }

            // 清洗 + 入库
            CleanContext cleanCtx = CleanContext.builder()
                    .sourceProjectNum(projectNum)
                    .dataType("ATTENDANCE")
                    .build();

            List<DiAttendance> batch = new ArrayList<>();
            for (JSONObject raw : pageData) {
                stats[0]++;
                try {
                    DiAttendance att = cleanPipeline.execute(raw, cleanCtx, "ATTENDANCE");
                    if (att != null) {
                        batch.add(att);
                    } else {
                        stats[2]++;
                    }
                } catch (Exception e) {
                    stats[2]++;
                    log.error("项目【{}】第{}页清洗失败: {}", projectNum, pageNum, e.getMessage());
                }
            }

            if (!batch.isEmpty()) {
                try {
                    // 批次大小与 pageSize 对齐（第三方 API 只支持 100）
                    int batchSize = properties.getPageSize();
                    int batchSuccess = batchInsertService.batchInsertOrUpdate(batch, attendanceService, batchSize);
                    stats[1] += batchSuccess;
                    stats[2] += (batch.size() - batchSuccess);

                    log.info("项目【{}】第{}页入库完成：本页{}条，成功{}条，累计进度 {}/{}",
                            projectNum, pageNum, batch.size(), batchSuccess,
                            stats[1], thirdPartyTotal);

                    for (DiAttendance att : batch) {
                        dataChangePublisher.publish("ATTENDANCE", projectNum,
                                att.getAttendanceId(), "CREATE");
                    }
                } catch (Exception e) {
                    log.error("项目【{}】第{}页入库失败: {}", projectNum, pageNum, e.getMessage());
                    stats[2] += batch.size();
                    return false;
                }
            }

            lastPage[0] = pageNum;
            progressStore.saveCurrentPage(projectNum, pageNum);
            progressStore.addTotalCount(projectNum, pageData.size());
            progressStore.addSuccessCount(projectNum, batch.size());

            return true;
        });

        // ===== 采集结束判断 =====
        boolean antiCrawler = Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"));

        if (antiCrawler) {
            log.warn("项目【{}】采集触发风控，本轮已采{}条/成功{}条，下次从第{}页续传",
                    projectNum, stats[0], stats[1], lastPage[0] + 1);
            markRateLimited(projectNum);
            progressStore.saveCurrentPage(projectNum, lastPage[0]);
            return false;
        }

        PollStatus currentStatus = progressStore.getStatus(projectNum);
        if (currentStatus == PollStatus.TOKEN_EXPIRED) {
            log.warn("项目【{}】Token 已失效，本轮已采{}条/成功{}条", projectNum, stats[0], stats[1]);
            progressStore.saveCurrentPage(projectNum, lastPage[0]);
            return false;
        }

        if (currentStatus == PollStatus.RATE_LIMITED) {
            log.warn("项目【{}】采集被中断（RATE_LIMITED），本轮已采{}条/成功{}条",
                    projectNum, stats[0], stats[1]);
            progressStore.saveCurrentPage(projectNum, lastPage[0]);
            return false;
        }

        // ===== Phase C：一致性比对 =====
        try {
            long finalLocalCount = attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .le(DiAttendance::getAttendanceTime, endTime)
                    .count();
            if (finalLocalCount < thirdPartyTotal) {
                log.warn("项目【{}】一致性比对：本地({})<第三方({})，差{}条，下一轮将自动补齐",
                        projectNum, finalLocalCount, thirdPartyTotal, thirdPartyTotal - finalLocalCount);
            } else {
                log.info("项目【{}】一致性比对通过：本地{}条 >= 第三方{}条",
                        projectNum, finalLocalCount, thirdPartyTotal);
            }
        } catch (Exception e) {
            log.warn("项目【{}】一致性比对查询失败: {}", projectNum, e.getMessage());
        }

        progressStore.saveStatus(projectNum, PollStatus.COMPLETED);

        log.info("项目【{}】第{}轮考勤采集完成，本轮新采{}条/成功{}条/失败{}条",
                projectNum, round, stats[0], stats[1], stats[2]);
        return true;
    }

    // =====================================================================
    // 本地数据量查询
    // =====================================================================

    /**
     * 查询本地时间范围内的考勤记录数
     */
    private long countLocalAttendance(String projectNum, LocalDateTime endTime) {
        try {
            return attendanceService.lambdaQuery()
                    .eq(DiAttendance::getSourceProjectNum, projectNum)
                    .le(DiAttendance::getAttendanceTime, endTime)
                    .count();
        } catch (Exception e) {
            log.warn("项目【{}】查询本地考勤数量失败: {}", projectNum, e.getMessage());
            return 0; // 查询失败时保守地从第1页开始
        }
    }

    // =====================================================================
    // 探针
    // =====================================================================

    /**
     * 带时间范围的探针：获取 ~endTime 范围内的 total
     */
    private int probeTotalInRange(String projectNum, String account, String password, String endTimeStr) {
        try {
            JSONObject probe = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, 1, 1, "", endTimeStr, account, password),
                    "ATTENDANCE", projectNum, 1, account);
            if (probe != null) {
                int total = probe.getInt("total", 0);
                log.info("项目【{}】时间范围 ~{} 内考勤总量：{}", projectNum, endTimeStr, total);
                return total;
            }
            return 0;
        } catch (Exception e) {
            log.error("项目【{}】考勤探针失败: {}", projectNum, e.getMessage());
            if (handleTokenExpired(projectNum, e, account)) {
                return -1;
            }
            if (isAntiCrawler(e)) {
                markRateLimited(projectNum);
                return -1;
            }
            return 0;
        }
    }

    /**
     * 探针最早考勤时间：翻到最后一页（DESC 排序），取最老的 clockingTime
     */
    private LocalDateTime probeEarliestClockingTime(String projectNum, String account,
                                                     String password, int total, int pageSize,
                                                     String endTimeStr) {
        int lastPageNum = (int) Math.ceil((double) total / pageSize);
        LocalDateTime earliest = null;
        try {
            JSONObject lastPage = rateLimitStrategy.executeWithRetry(
                    () -> apiClient.getAttendancePage(projectNum, lastPageNum, pageSize,
                            "", endTimeStr, account, password),
                    "ATTENDANCE", projectNum, lastPageNum, account);
            if (lastPage != null) {
                cn.hutool.json.JSONArray list = lastPage.getJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String ct = list.getJSONObject(i).getStr("clockingTime");
                        if (ct != null && !ct.isEmpty()) {
                            try {
                                LocalDateTime t = LocalDateTime.parse(ct, DT_FMT);
                                if (earliest == null || t.isBefore(earliest)) {
                                    earliest = t;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (earliest != null) {
                log.info("项目【{}】最早考勤时间：{}（从最后一页推算）", projectNum, earliest);
            }
            return earliest;
        } catch (Exception e) {
            log.error("项目【{}】获取最早考勤时间失败: {}", projectNum, e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private boolean handleTokenExpired(String projectNum, Throwable e, String account) {
        if (AntiCrawlerDetector.isTokenExpired(e)) {
            log.error("项目【{}】账号【{}】Token已过期（401登录过期），立即移除Token", projectNum, account);
            tokenManager.removeToken(account);
            progressStore.saveStatus(projectNum, PollStatus.TOKEN_EXPIRED);
            return true;
        }
        return false;
    }

    private boolean isAntiCrawler(Throwable e) {
        return AntiCrawlerDetector.isAntiCrawler(e);
    }

    private void markRateLimited(String projectNum) {
        long cooldownMs = properties.getRateLimitCooldownSeconds() * 1000L;
        long untilMs = System.currentTimeMillis() + cooldownMs;
        progressStore.saveRateLimitUntil(projectNum, untilMs);
        progressStore.saveStatus(projectNum, PollStatus.RATE_LIMITED);
        log.warn("项目【{}】标记为 RATE_LIMITED，冷却至 {} 秒后",
                projectNum, properties.getRateLimitCooldownSeconds());
    }
}

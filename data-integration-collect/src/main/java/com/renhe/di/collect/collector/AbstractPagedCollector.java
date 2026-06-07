package com.renhe.di.collect.collector;

import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;
import com.renhe.di.collect.strategy.RateLimitStrategy;
import com.renhe.di.core.exception.AntiCrawlerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分页采集器抽象模板
 *
 * @param <T> 原始数据类型
 */
@Slf4j
public abstract class AbstractPagedCollector<T> implements PagedDataCollector<T> {

    @Autowired
    private RateLimitStrategy rateLimitStrategy;

    /** 连续跳过页面达到此阈值时，触发跳跃式前进（跳过无关数据区域） */
    private static final int CONSECUTIVE_SKIP_JUMP_THRESHOLD = 3;
    /** 跳跃步长（每次跳跃前进的页数） */
    private static final int JUMP_STRIDE = 5;

    /**
     * 获取分页大小（子类可覆盖）
     *
     * @return 分页大小
     */
    protected int getPageSize() {
        return 100;
    }

    /**
     * 采集所有数据（自动分页）
     * <p>
     * 集成了限流、账号级并发控制、智能重试机制
     *
     * @param ctx 采集上下文
     * @return 全部数据
     */
    public List<T> collectAll(CollectContext ctx) {
        List<T> allData = new ArrayList<>();
        int pageNum = 1;
        int pageSize = getPageSize();
        String account = ctx.getAccount();

        // 获取账号级并发信号量
        if (!rateLimitStrategy.tryAcquire(account)) {
            log.error("[{}] 项目【{}】获取账号并发许可超时，跳过采集", getDataType(), ctx.getSourceProjectNum());
            return allData;
        }

        try {
            while (true) {
                PageResult<T> pageResult;
                final int currentPage = pageNum;
                try {
                    pageResult = rateLimitStrategy.executeWithRetry(
                            () -> collectPage(ctx, currentPage, pageSize),
                            getDataType(),
                            ctx.getSourceProjectNum(),
                            currentPage,
                            account
                    );
                } catch (Exception e) {
                    if (isAntiCrawlerException(e)) {
                        ctx.putExtraParam("_antiCrawlerTriggered", true);
                        log.error("[{}] 项目【{}】第{}页触发风控，终止采集: {}",
                                getDataType(), ctx.getSourceProjectNum(), pageNum, e.getMessage());
                    } else {
                        log.error("[{}] 项目【{}】第{}页采集失败: {}",
                                getDataType(), ctx.getSourceProjectNum(), pageNum, e.getMessage());
                    }
                    break;
                }

                // 首页响应中记录第三方平台数据总量
                if (pageNum == 1 && pageResult != null) {
                    ctx.putExtraParam("_thirdPartyTotal", pageResult.getTotal());
                    log.info("[{}] 项目【{}】第三方平台数据总量: {}", getDataType(), ctx.getSourceProjectNum(), pageResult.getTotal());
                }

                if (pageResult == null || pageResult.getList() == null || pageResult.getList().isEmpty()) {
                    log.info("[{}] 项目【{}】第{}页无数据，采集结束", getDataType(), ctx.getSourceProjectNum(), pageNum);
                    break;
                }

                allData.addAll(pageResult.getList());
                log.info("[{}] 项目【{}】第{}页采集完成，本页{}条，总数：{}",
                        getDataType(), ctx.getSourceProjectNum(), pageNum,
                        pageResult.getList().size(),
                        pageResult.getTotal());

                if (!pageResult.isHasNext()) {
                    log.info("[{}] 项目【{}】分页采集完成，共{}页，总数：{}",
                            getDataType(), ctx.getSourceProjectNum(), pageNum, pageResult.getTotal());
                    break;
                }
                pageNum++;
            }

            return allData;
        } finally {
            rateLimitStrategy.release(account);
        }
    }

    /**
     * 采集所有数据（分批并发分页采集）
     * <p>
     * Phase 1: 探针请求第1页，获取 total
     * Phase 2: 按批次拆分页面范围
     * Phase 3: 批次内并发请求（Semaphore 控制并发数 + 随机抖动）
     * Phase 4: 结果聚合
     *
     * @param ctx      采集上下文
     * @param executor 线程池执行器
     * @return 全部数据
     */
    public List<T> collectAllConcurrent(CollectContext ctx, ExecutorService executor) {
        String account = ctx.getAccount();
        String projectNum = ctx.getSourceProjectNum();
        String dataType = getDataType();
        int pageSize = getPageSize();
        int maxConcurrency = rateLimitStrategy.getMaxConcurrency(dataType);
        int batchSize = rateLimitStrategy.getBatchSize(dataType);

        // ===== Phase 1: 探针请求第1页，获取 total =====
        // 支持调用方预加载探针数据（避免 Phase A 探针与本方法内部探针重复请求）
        PageResult<T> probeResult;
        @SuppressWarnings("unchecked")
        PageResult<T> preloaded = (PageResult<T>) ctx.getExtraParam("_preloadedFirstPage");
        if (preloaded != null) {
            probeResult = preloaded;
            log.info("[{}] 项目【{}】使用预加载探针数据（跳过重复请求第1页）",
                    dataType, projectNum);
        } else {
            try {
                probeResult = rateLimitStrategy.executeWithRetry(
                        () -> collectPage(ctx, 1, pageSize),
                        dataType, projectNum, 1, account
                );
            } catch (Exception e) {
                log.error("[{}] 项目【{}】探针请求第1页失败: {}", dataType, projectNum, e.getMessage());
                return Collections.emptyList();
            }
        }

            if (probeResult == null || probeResult.getList() == null || probeResult.getList().isEmpty()) {
                log.info("[{}] 项目【{}】第1页无数据，采集结束", dataType, projectNum);
                return Collections.emptyList();
            }

            int total = probeResult.getTotal();
            ctx.putExtraParam("_thirdPartyTotal", total);
            int probePageSize = probeResult.getList().size();

            // 如果探针页 < pageSize，说明只有1页（total不可靠，以实际数据为准）
            if (probePageSize < pageSize) {
                log.info("[{}] 项目【{}】仅1页{}条，采集完成（API返回total={}）",
                        dataType, projectNum, probePageSize, total);
                return new ArrayList<>(probeResult.getList());
            }

            log.info("[{}] 项目【{}】探针页{}条，API返回total={}，开始动态分批采集（直到某页<{}条即停止）",
                    dataType, projectNum, probePageSize, total, pageSize);

            // 探针页内容级判断：跳过（未到目标范围）或终止（已越过目标范围）
            boolean probeSkipped = shouldSkipPage(probeResult.getList(), ctx);
            if (isBeyondTimeRange(probeResult.getList(), ctx)) {
                log.info("[{}] 项目【{}】探针页已超出时间范围，终止（仅需1页{}条）",
                        dataType, projectNum, probePageSize);
                return new ArrayList<>(probeResult.getList());
            }

            // ===== Phase 2 & 3: 按批次拆分并并发执行（动态页码，不信任 total） =====
            List<T> allData = probeSkipped
                    ? new CopyOnWriteArrayList<>()
                    : new CopyOnWriteArrayList<>(probeResult.getList());
            if (probeSkipped) {
                log.info("[{}] 项目【{}】探针页数据尚未到达目标时间范围，跳过，继续取后续页",
                        dataType, projectNum);
            }
            int consecutiveSkips = 0;
            int totalSkippedPages = probeSkipped ? 1 : 0;
            int jumpCount = 0;
            int lastSkippedPage = probeSkipped ? 1 : 0;
            AtomicInteger completedPages = new AtomicInteger(1); // 第1页已采集
            AtomicInteger batchCount = new AtomicInteger(0);
            AtomicBoolean reachedEnd = new AtomicBoolean(false); // 动态终点标记

            int startPage = 2;
            // 初始化共享断路器：批次内任一请求触发风控时，其他飞行请求立即停止
            ctx.getExtraParams().computeIfAbsent("_circuitBreaker", k -> new AtomicBoolean(false));
            while (!reachedEnd.get()) {
                int endPage = startPage + batchSize - 1;
                int currentBatch = batchCount.incrementAndGet();
                int pagesInBatch = endPage - startPage + 1;

                log.info("[{}] 项目【{}】开始执行第{}批次，页码范围 [{}-{}]，共{}页",
                        dataType, projectNum, currentBatch, startPage, endPage, pagesInBatch);

                long batchStart = System.currentTimeMillis();

                // 批次内并发：每个页面一个 CompletableFuture，Semaphore 控制执行速率
                List<CompletableFuture<List<T>>> futures = new ArrayList<>();
                for (int page = startPage; page <= endPage; page++) {
                    final int targetPage = page;
                    CompletableFuture<List<T>> future = CompletableFuture.supplyAsync(() -> {
                        // 断路器检查：同批次其他请求已触发风控，立即跳过
                        Object cb = ctx.getExtraParam("_circuitBreaker");
                        if (cb instanceof AtomicBoolean && ((AtomicBoolean) cb).get()) {
                            log.debug("[{}] 项目【{}】第{}页检测到断路器已触发，跳过请求",
                                    dataType, projectNum, targetPage);
                            return Collections.emptyList();
                        }
                        // 先获取执行许可（阻塞等待，控制并发数）
                        if (!rateLimitStrategy.tryAcquire(account, dataType, 1)) {
                            log.warn("[{}] 项目【{}】第{}页获取执行许可超时，跳过", dataType, projectNum, targetPage);
                            return Collections.emptyList();
                        }
                        try {
                            // 随机抖动，分散请求时间
                            rateLimitStrategy.applyJitter();

                            PageResult<T> result = rateLimitStrategy.executeWithRetry(
                                    () -> collectPage(ctx, targetPage, pageSize),
                                    dataType, projectNum, targetPage, account
                            );
                            if (result != null && result.getList() != null) {
                                int done = completedPages.incrementAndGet();
                                log.info("[{}] 项目【{}】第{}页采集完成（已采集{}页）",
                                        dataType, projectNum, targetPage, done);
                                return result.getList();
                            }
                        } catch (Exception e) {
                            if (isAntiCrawlerException(e)) {
                                ctx.putExtraParam("_antiCrawlerTriggered", true);
                                // 触发断路器：通知同批次其他飞行请求立即停止
                                if (cb instanceof AtomicBoolean) {
                                    ((AtomicBoolean) cb).set(true);
                                }
                                log.error("[{}] 项目【{}】第{}页并发采集触发风控，终止采集: {}",
                                        dataType, projectNum, targetPage, e.getMessage());
                            } else {
                                log.error("[{}] 项目【{}】第{}页并发采集失败: {}",
                                        dataType, projectNum, targetPage, e.getMessage());
                            }
                        } finally {
                            rateLimitStrategy.release(account, dataType, 1);
                        }
                        return Collections.emptyList();
                    }, executor);
                    futures.add(future);
                }

                // 等待本批次全部完成并聚合结果
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    // CompletableFuture.allOf().join() 会将异常包装在 CompletionException 中
                    if (isAntiCrawlerException(e)) {
                        ctx.putExtraParam("_antiCrawlerTriggered", true);
                        log.error("[{}] 项目【{}】批次内触发风控，终止当前项目采集", dataType, projectNum);
                        break;
                    }
                    // 其他异常继续聚合已有结果
                }
                for (CompletableFuture<List<T>> future : futures) {
                    try {
                        if (future.isCompletedExceptionally()) {
                            future.exceptionally(ex -> {
                                if (ex.getCause() instanceof AntiCrawlerException) {
                                }
                                return Collections.emptyList();
                            }).get();
                        } else {
                            List<T> pageList = future.get();

                            // 内容级跳过：子类可决定该页数据是否尚未到达目标范围（如：页全在月末之后）
                            if (shouldSkipPage(pageList, ctx)) {
                                consecutiveSkips++;
                                totalSkippedPages++;
                                lastSkippedPage = startPage;
                                log.info("[{}] 项目【{}】跳过该页数据（尚未到达目标时间范围，连续跳过{}页）",
                                        dataType, projectNum, consecutiveSkips);
                                // 连续跳过达到阈值 → 跳跃前进，快速越过无关数据区域
                                if (consecutiveSkips >= CONSECUTIVE_SKIP_JUMP_THRESHOLD) {
                                    int jumpTarget = startPage + JUMP_STRIDE;
                                    log.info("[{}] 项目【{}】连续跳过{}页，跳跃前进到第{}页（回溯填充第{}-{}页）",
                                            dataType, projectNum, consecutiveSkips, jumpTarget,
                                            lastSkippedPage + 1, jumpTarget - 1);
                                    startPage = jumpTarget;
                                    jumpCount++;
                                    // 回溯：下一批次从最后跳过页的下一页开始顺序填充，确保不遗漏目标数据
                                    if (lastSkippedPage > 0) {
                                        startPage = lastSkippedPage + 1;
                                    }
                                    consecutiveSkips = 0;
                                }
                                continue;
                            }
                            consecutiveSkips = 0; // 非跳过页，重置连续跳过计数

                            allData.addAll(pageList);
                            // 动态终点：某页返回 < pageSize 条 → 最后一页
                            if (pageList.size() < pageSize) {
                                reachedEnd.set(true);
                                log.debug("[{}] 项目【{}】检测到末页（{}条 < {}），批内后续页将跳过",
                                        dataType, projectNum, pageList.size(), pageSize);
                            } else if (pageList.isEmpty()) {
                                reachedEnd.set(true);
                            }
                            // 内容级终止：子类可检测数据是否已超出时间范围
                            if (!reachedEnd.get() && isBeyondTimeRange(pageList, ctx)) {
                                reachedEnd.set(true);
                                log.info("[{}] 项目【{}】检测到数据超出时间范围，终止后续分页",
                                        dataType, projectNum);
                            }
                        }
                    } catch (Exception e) {
                        log.error("[{}] 项目【{}】批次结果聚合异常: {}", dataType, projectNum, e.getMessage());
                    }
                }

                // 断路器检查：本批次内已触发风控，终止后续批次
                if (Boolean.TRUE.equals(ctx.getExtraParam("_antiCrawlerTriggered"))) {
                    log.info("[{}] 项目【{}】批次内检测到风控触发，终止后续批次",
                            dataType, projectNum);
                    break;
                }

                long batchDuration = (System.currentTimeMillis() - batchStart) / 1000;
                log.info("[{}] 项目【{}】第{}批次完成，耗时{}秒，已采集{}页",
                        dataType, projectNum, currentBatch, batchDuration,
                        completedPages.get());

                // 批次间冷却（未到终点时）
                if (!reachedEnd.get()) {
                    rateLimitStrategy.applyInterBatchDelay(dataType);
                }

                startPage = endPage + 1;
            }

            log.info("[{}] 项目【{}】动态分批采集完成，共{}页，累计{}条数据（跳过{}页，跳跃{}次）",
                    dataType, projectNum, completedPages.get(), allData.size(), totalSkippedPages, jumpCount);
            return new ArrayList<>(allData);
    }

    /**
     * 内容级终止钩子：子类可覆盖以检测数据是否已超出时间范围等条件
     * @param pageData 本页数据
     * @param ctx 采集上下文
     * @return true 表示应该终止后续分页
     */
    protected boolean isBeyondTimeRange(List<T> pageData, CollectContext ctx) {
        return false;
    }

    /**
     * 内容级跳过钩子：子类可覆盖以检测该页数据是否尚未到达目标范围
     * @param pageData 本页数据
     * @param ctx 采集上下文
     * @return true 表示该页数据应被跳过（但不终止，继续取后续页）
     */
    protected boolean shouldSkipPage(List<T> pageData, CollectContext ctx) {
        return false;
    }

    /**
     * 遍历整个异常链，检查是否包含风控/反爬虫关键词
     * 比 instanceof AntiCrawlerException 更可靠，因为异常可能被 CompletableFuture 等框架包装
     */
    private static final String[] ANTI_CRAWLER_KEYWORDS = {
            "触发风控", "触发反爬虫", "风控", "反爬虫",
            "系统异常", "请联系管理员"
    };

    private boolean isAntiCrawlerException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof AntiCrawlerException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                for (String keyword : ANTI_CRAWLER_KEYWORDS) {
                    if (msg.contains(keyword)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }
}

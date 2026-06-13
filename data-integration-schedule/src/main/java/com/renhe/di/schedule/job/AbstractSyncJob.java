package com.renhe.di.schedule.job;

import com.renhe.di.collect.api.AntiCrawlerDetector;
import com.renhe.di.collect.api.TokenManager;
import com.renhe.di.dispatch.alarm.WechatAlarmService;
import com.renhe.di.store.service.SyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 同步任务抽象基类
 * 使用Spring @Scheduled替代XXL-JOB
 */
@Slf4j
public abstract class AbstractSyncJob {

    @Autowired
    protected SyncLogService syncLogService;

    @Autowired
    protected WechatAlarmService alarmService;

    @Autowired
    protected TokenManager tokenManager;

    /**
     * 执行同步任务（由子类通过@Scheduled触发）
     * <p>
     * 不使用全局锁：项目间并发由 projectSyncExecutor 控制，
     * 同账号限流由 RateLimitStrategy Semaphore 保障，
     * 数据操作均为幂等 UPSERT。
     */
    public void execute() throws Exception {
        String projectNum = null;
        Long logId = null;
        try {
            logId = syncLogService.startLog(getTaskName(), getSyncType(), getDataType(), projectNum);
            log.info("任务开始: {}", getTaskName());

            SyncResult result = doSync(projectNum);

            syncLogService.completeLog(logId, result.getTotalCount(), result.getSuccessCount(),
                    result.getFailCount(), result.getSkipCount());
            log.info("任务完成: {} 总计:{} 成功:{} 失败:{} 跳过:{}",
                    getTaskName(), result.getTotalCount(), result.getSuccessCount(),
                    result.getFailCount(), result.getSkipCount());
        } catch (Exception e) {
            log.error("[{}] 同步任务执行失败: {}", getTaskName(), e.getMessage(), e);
            if (logId != null) {
                syncLogService.failLog(logId, e);
            }
            // Token过期（401）→ 告警通知
            if (AntiCrawlerDetector.isTokenExpired(e)) {
                log.error("[{}] Token已过期（401登录过期），已终止任务", getTaskName());
            }
            // 发送企业微信告警
            try {
                alarmService.sendSyncAlarm("ALL", getDataType(), e.getMessage());
            } catch (Exception alarmEx) {
                log.error("告警发送失败", alarmEx);
            }
        }
    }

    protected String parseProjectNum(String jobParam) {
        if (jobParam == null || jobParam.isEmpty()) {
            return null;
        }
        return jobParam.trim();
    }

    protected abstract String getDataType();

    protected abstract String getSyncType();

    protected abstract String getTaskName();

    protected abstract SyncResult doSync(String projectNum);

    /**
     * 检测异常是否为风控/反爬虫触发（遍历 cause chain）
     * 委托给全局统一的 {@link AntiCrawlerDetector}
     */
    protected boolean isAntiCrawlerMessage(Throwable e) {
        return AntiCrawlerDetector.isAntiCrawler(e);
    }

    /**
     * 检测异常是否为 Token 过期（401登录过期），若是则移除 Token
     *
     * @param e       异常
     * @param account 关联账号
     * @return true 表示 Token 已过期并已移除
     */
    protected boolean handleTokenExpired(Throwable e, String account) {
        if (AntiCrawlerDetector.isTokenExpired(e)) {
            log.error("[{}] 账号【{}】Token已过期（401登录过期），立即移除Token", getTaskName(), account);
            tokenManager.removeToken(account);
            return true;
        }
        return false;
    }
}

package com.renhe.di.schedule.job;

import com.renhe.di.dispatch.alarm.WechatAlarmService;
import com.renhe.di.store.service.SyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 同步任务抽象基类
 * 使用Spring @Scheduled替代XXL-JOB
 */
@Slf4j
public abstract class AbstractSyncJob {

    @Autowired
    protected SyncLogService syncLogService;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected WechatAlarmService alarmService;

    /**
     * 执行同步任务（由子类通过@Scheduled触发）
     */
    public void execute() throws Exception {
        String projectNum = null;

        String lockKey = "sync:lock:" + getDataType() + ":" + (projectNum != null ? projectNum : "all");
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(locked)) {
            log.warn("任务正在执行中，跳过本次调度: {}", getTaskName());
            return;
        }

        Long logId = null;
        try {
            logId = syncLogService.startLog(getTaskName(), getSyncType(), getDataType(), projectNum);
            log.info("任务开始: {} 项目: {}", getTaskName(), projectNum);

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
            // 发送企业微信告警
            try {
                alarmService.sendSyncAlarm(projectNum != null ? projectNum : "ALL", getDataType(), e.getMessage());
            } catch (Exception alarmEx) {
                log.error("告警发送失败", alarmEx);
            }
        } finally {
            redisTemplate.delete(lockKey);
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

    /** 风控/反爬虫消息关键词 */
    private static final String[] ANTI_CRAWLER_MSG_KEYWORDS = {
            "触发风控", "触发反爬虫", "风控", "反爬虫",
            "账号异常", "频繁", "限制", "系统异常", "请联系管理员"
    };

    /**
     * 检测异常消息是否包含风控/反爬虫关键词（遍历 cause chain）
     */
    protected boolean isAntiCrawlerMessage(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                for (String keyword : ANTI_CRAWLER_MSG_KEYWORDS) {
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

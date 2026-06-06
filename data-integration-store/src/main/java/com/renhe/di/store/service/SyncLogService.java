package com.renhe.di.store.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.DiSyncLog;

import java.time.LocalDateTime;

/**
 * 同步日志服务
 */
public interface SyncLogService extends IService<DiSyncLog> {

    /**
     * 记录任务开始
     */
    Long startLog(String taskName, String taskType, String dataType, String sourceProjectNum);

    /**
     * 记录任务完成
     */
    void completeLog(Long logId, int totalCount, int successCount, int failCount, int skipCount);

    /**
     * 记录任务失败
     */
    void failLog(Long logId, Exception e);

    /**
     * 获取最后同步时间
     */
    LocalDateTime getLastSyncTime(String dataType, String projectNum);
}

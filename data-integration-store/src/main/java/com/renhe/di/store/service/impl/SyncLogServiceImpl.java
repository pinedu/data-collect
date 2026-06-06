package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.renhe.di.store.entity.DiSyncLog;
import com.renhe.di.store.mapper.DiSyncLogMapper;
import com.renhe.di.store.service.SyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 同步日志服务实现
 */
@Slf4j
@Service
public class SyncLogServiceImpl extends ServiceImpl<DiSyncLogMapper, DiSyncLog> implements SyncLogService {

    @Override
    public Long startLog(String taskName, String taskType, String dataType, String sourceProjectNum) {
        DiSyncLog syncLog = new DiSyncLog();
        syncLog.setTaskName(taskName);
        syncLog.setTaskType(taskType);
        syncLog.setDataType(dataType);
        syncLog.setSourceProjectNum(sourceProjectNum);
        syncLog.setStartTime(LocalDateTime.now());
        syncLog.setStatus("RUNNING");
        syncLog.setTotalCount(0);
        syncLog.setSuccessCount(0);
        syncLog.setFailCount(0);
        syncLog.setSkipCount(0);
        save(syncLog);
        return syncLog.getId();
    }

    @Override
    public void completeLog(Long logId, int totalCount, int successCount, int failCount, int skipCount) {
        DiSyncLog syncLog = getById(logId);
        if (syncLog == null) {
            return;
        }
        syncLog.setEndTime(LocalDateTime.now());
        syncLog.setTotalCount(totalCount);
        syncLog.setSuccessCount(successCount);
        syncLog.setFailCount(failCount);
        syncLog.setSkipCount(skipCount);
        syncLog.setStatus(failCount > 0 ? "PARTIAL" : "SUCCESS");
        updateById(syncLog);
    }

    @Override
    public void failLog(Long logId, Exception e) {
        DiSyncLog syncLog = getById(logId);
        if (syncLog == null) {
            return;
        }
        syncLog.setEndTime(LocalDateTime.now());
        syncLog.setStatus("FAIL");
        String msg = e.getMessage();
        syncLog.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        updateById(syncLog);
    }

    @Override
    public LocalDateTime getLastSyncTime(String dataType, String projectNum) {
        LocalDateTime time = baseMapper.selectLastSyncTime(dataType, projectNum);
        return time != null ? time : LocalDateTime.now().minusDays(7);
    }
}

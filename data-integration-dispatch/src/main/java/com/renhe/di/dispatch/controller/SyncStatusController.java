package com.renhe.di.dispatch.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.renhe.di.api.vo.SyncStatusVO;
import com.renhe.di.core.model.Result;
import com.renhe.di.store.entity.DiSyncLog;
import com.renhe.di.store.service.SyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 同步状态查询接口
 */
@Slf4j
@RestController
@RequestMapping("/v1/sync")
public class SyncStatusController {

    @Autowired
    private SyncLogService syncLogService;

    /**
     * 查询同步状态
     */
    @GetMapping("/status")
    public Result<SyncStatusVO> querySyncStatus(@RequestParam String projectNum,
                                                 @RequestParam String dataType) {
        LambdaQueryWrapper<DiSyncLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiSyncLog::getSourceProjectNum, projectNum)
                .eq(DiSyncLog::getDataType, dataType)
                .orderByDesc(DiSyncLog::getStartTime)
                .last("LIMIT 1");

        DiSyncLog log = syncLogService.getOne(wrapper);
        if (log == null) {
            return Result.success(SyncStatusVO.builder()
                    .sourceProjectNum(projectNum)
                    .dataType(dataType)
                    .status("NONE")
                    .build());
        }

        SyncStatusVO vo = SyncStatusVO.builder()
                .sourceProjectNum(log.getSourceProjectNum())
                .dataType(log.getDataType())
                .syncType(log.getTaskType())
                .status(log.getStatus())
                .lastSyncTime(log.getEndTime())
                .totalCount(log.getTotalCount())
                .successCount(log.getSuccessCount())
                .failCount(log.getFailCount())
                .errorMsg(log.getErrorMsg())
                .build();

        return Result.success(vo);
    }

    /**
     * 查询最近同步记录
     */
    @GetMapping("/logs")
    public Result<List<DiSyncLog>> querySyncLogs(@RequestParam String projectNum,
                                                  @RequestParam(defaultValue = "10") int limit) {
        LambdaQueryWrapper<DiSyncLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiSyncLog::getSourceProjectNum, projectNum)
                .orderByDesc(DiSyncLog::getStartTime)
                .last("LIMIT " + limit);

        List<DiSyncLog> list = syncLogService.list(wrapper);
        return Result.success(list);
    }
}

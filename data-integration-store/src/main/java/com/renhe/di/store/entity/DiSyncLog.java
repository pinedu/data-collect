package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 同步任务日志实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_sync_log")
public class DiSyncLog extends BaseEntity {

    /** 主键 ID */
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 任务类型（如 FULL_SYNC / INCREMENTAL_SYNC） */
    private String taskType;

    /** 数据类型（PERSON / ATTENDANCE / TEAM / PAYROLL 等） */
    private String dataType;

    /** 源平台项目编号 */
    private String sourceProjectNum;

    /** 任务开始时间 */
    private LocalDateTime startTime;

    /** 任务结束时间 */
    private LocalDateTime endTime;

    /** 本次处理总条数 */
    private Integer totalCount;

    /** 成功条数 */
    private Integer successCount;

    /** 失败条数 */
    private Integer failCount;

    /** 跳过条数（数据未变化） */
    private Integer skipCount;

    /** 任务状态（SUCCESS / FAIL / RUNNING） */
    private String status;

    /** 错误信息（失败时记录） */
    private String errorMsg;
}

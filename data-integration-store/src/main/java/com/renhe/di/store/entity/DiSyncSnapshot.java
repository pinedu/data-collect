package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 同步快照实体
 * 记录每次同步完成后，第三方平台与本地平台各业务的数据总量对比
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_sync_snapshot")
public class DiSyncSnapshot extends BaseEntity {

    /** 主键 ID */
    private Long id;

    /** 源平台项目编号 */
    private String sourceProjectNum;

    /** 数据类型（TEAM/PERSON/ATTENDANCE/PAYROLL/PAYROLL_DETAIL） */
    private String dataType;

    /** 第三方平台数据总量 */
    private Integer thirdPartyTotal;

    /** 本地平台数据总量 */
    private Integer localTotal;

    /** 同步时间 */
    private LocalDateTime syncTime;

    /** 备注 */
    private String remark;

    /** 月份标识（yyyy-MM 格式），为 null 时表示非月份级快照（兼容旧逻辑） */
    private String monthId;

    /** 月份同步进度：该月份已同步到的最早考勤时间 */
    private LocalDateTime monthSyncDate;

    /** 月份第三方数据总量（该时间范围内的第三方数据条数） */
    private Integer monthThirdPartyTotal;

    /** 已采集到的最后一页页码（0=未开始，null=非月份级快照） */
    private Integer lastCollectedPage;

    /** 月份第三方数据总页数（ceil(monthThirdPartyTotal / pageSize)） */
    private Integer monthTotalPages;
}

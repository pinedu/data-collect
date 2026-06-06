package com.renhe.di.api.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步看板VO - 单个业务的同步数据对比
 */
@Data
@Builder
public class SyncDashboardItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据类型 */
    private String dataType;

    /** 数据类型中文名 */
    private String dataTypeName;

    /** 第三方平台数据总量 */
    private Integer thirdPartyTotal;

    /** 本地平台数据总量 */
    private Integer localTotal;

    /** 最近同步时间 */
    private LocalDateTime lastSyncTime;

    /** 差异率（百分比） */
    private String diffRate;
}

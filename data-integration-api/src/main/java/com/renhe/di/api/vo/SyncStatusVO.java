package com.renhe.di.api.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步状态VO
 */
@Data
@Builder
public class SyncStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceProjectNum;
    private String projectName;
    private String dataType;
    private String syncType;
    private String status;
    private LocalDateTime lastSyncTime;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String errorMsg;
}

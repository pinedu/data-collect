package com.renhe.di.api.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据变更事件VO
 */
@Data
@Builder
public class DataChangeEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String dataType;
    private String projectNum;
    private String dataId;
    private String action;
    private LocalDateTime timestamp;
    private Object data;
}

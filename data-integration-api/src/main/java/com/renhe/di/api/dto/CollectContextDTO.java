package com.renhe.di.api.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 采集上下文DTO
 */
@Data
@Builder
public class CollectContextDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceProjectNum;
    private String account;
    private String password;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    @Builder.Default
    private Map<String, Object> extraParams = new HashMap<>();

    public Object getExtraParam(String key) {
        return extraParams != null ? extraParams.get(key) : null;
    }

    public void putExtraParam(String key, Object value) {
        if (extraParams == null) {
            extraParams = new HashMap<>();
        }
        extraParams.put(key, value);
    }
}

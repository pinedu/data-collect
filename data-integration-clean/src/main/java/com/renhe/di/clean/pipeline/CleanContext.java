package com.renhe.di.clean.pipeline;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 清洗上下文
 */
@Data
@Builder
public class CleanContext {

    private String sourceProjectNum;
    private String dataType;

    @Builder.Default
    private Map<String, Object> extraContext = new HashMap<>();

    public Object get(String key) {
        return extraContext != null ? extraContext.get(key) : null;
    }

    public void put(String key, Object value) {
        if (extraContext == null) {
            extraContext = new HashMap<>();
        }
        extraContext.put(key, value);
    }
}

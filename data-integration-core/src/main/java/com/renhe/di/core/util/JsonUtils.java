package com.renhe.di.core.util;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON工具类
 */
@Slf4j
public class JsonUtils {

    private JsonUtils() {
    }

    public static JSONObject parseObject(String json) {
        if (json == null || json.isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            log.warn("JSON解析失败: {}", json);
            return new JSONObject();
        }
    }

    public static String toJsonString(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return JSONUtil.toJsonStr(obj);
        } catch (Exception e) {
            log.warn("JSON序列化失败", e);
            return "{}";
        }
    }

    public static boolean isValidJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        return JSONUtil.isTypeJSON(json);
    }
}

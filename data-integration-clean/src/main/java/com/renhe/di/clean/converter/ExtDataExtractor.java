package com.renhe.di.clean.converter;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 扩展字段提取器 —— 保存第三方接口返回的完整原始JSON，用于数据排查
 */
@Slf4j
@Component
public class ExtDataExtractor {

    /**
     * 提取完整的原始数据到extData
     *
     * @param rawData 原始JSON数据
     * @return 完整原始数据副本
     */
    public JSONObject extractExtData(JSONObject rawData) {
        if (rawData == null) {
            return new JSONObject();
        }
        return new JSONObject(rawData);
    }
}

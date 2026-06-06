package com.renhe.di.clean.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 清洗流水线
 * 通过 CleanPipelineConfig 手动注册清洗器
 */
@Slf4j
public class CleanPipeline {

    private final List<DataCleaner<?, ?>> cleaners = new ArrayList<>();

    /**
     * 注册清洗器
     *
     * @param cleaner 清洗器
     */
    public void registerCleaner(DataCleaner<?, ?> cleaner) {
        cleaners.add(cleaner);
        log.info("注册清洗器: {}", cleaner.getClass().getSimpleName());
    }

    /**
     * 执行清洗
     *
     * @param rawData  原始数据
     * @param ctx      清洗上下文
     * @param dataType 数据类型
     * @param <T>      原始类型
     * @param <R>      目标类型
     * @return 清洗结果
     */
    @SuppressWarnings("unchecked")
    public <T, R> R execute(T rawData, CleanContext ctx, String dataType) {
        if (rawData == null) {
            return null;
        }

        Object current = rawData;
        for (DataCleaner<?, ?> cleaner : cleaners) {
            if (cleaner.supports(dataType)) {
                try {
                    DataCleaner<Object, Object> c = (DataCleaner<Object, Object>) cleaner;
                    current = c.clean(current, ctx);
                    if (current == null) {
                        log.warn("[{}] 数据在清洗器【{}】中被过滤", dataType, cleaner.getClass().getSimpleName());
                        return null;
                    }
                } catch (Exception e) {
                    log.error("[{}] 清洗器【{}】执行失败: {}", dataType, cleaner.getClass().getSimpleName(), e.getMessage());
                    throw new RuntimeException("数据清洗失败: " + e.getMessage(), e);
                }
            }
        }

        return (R) current;
    }

    /**
     * 批量清洗
     *
     * @param rawDataList 原始数据列表
     * @param ctx         清洗上下文
     * @param dataType    数据类型
     * @param <T>         原始类型
     * @param <R>         目标类型
     * @return 清洗结果列表
     */
    public <T, R> List<R> executeBatch(List<T> rawDataList, CleanContext ctx, String dataType) {
        List<R> resultList = new ArrayList<>();
        for (T rawData : rawDataList) {
            R cleaned = execute(rawData, ctx, dataType);
            if (cleaned != null) {
                resultList.add(cleaned);
            }
        }
        return resultList;
    }
}

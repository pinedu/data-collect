package com.renhe.di.clean.pipeline;

/**
 * 数据清洗器接口
 *
 * @param <T> 原始数据类型
 * @param <R> 清洗后数据类型
 */
public interface DataCleaner<T, R> {

    /**
     * 清洗数据
     *
     * @param rawData 原始数据
     * @param ctx     清洗上下文
     * @return 清洗后的数据
     */
    R clean(T rawData, CleanContext ctx);

    /**
     * 是否支持该数据类型
     *
     * @param dataType 数据类型
     * @return true-支持
     */
    boolean supports(String dataType);
}

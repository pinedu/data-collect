package com.renhe.di.collect.collector;

import com.renhe.di.collect.model.CollectContext;
import com.renhe.di.collect.model.PageResult;

/**
 * 分页数据采集器接口
 *
 * @param <T> 原始数据类型
 */
public interface PagedDataCollector<T> {

    /**
     * 采集单页数据
     *
     * @param ctx      采集上下文
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    PageResult<T> collectPage(CollectContext ctx, int pageNum, int pageSize);

    /**
     * 是否支持时间范围查询
     *
     * @return true-支持
     */
    boolean supportTimeRange();

    /**
     * 获取数据唯一标识
     *
     * @param data 数据对象
     * @return 唯一标识
     */
    String getDataId(T data);

    /**
     * 获取数据类型标识
     *
     * @return 如 PROJECT/TEAM/PERSON/ATTENDANCE/PAYROLL
     */
    String getDataType();
}

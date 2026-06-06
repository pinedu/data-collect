package com.renhe.di.store.mapper;

import com.renhe.di.store.service.impl.BatchInsertServiceImpl;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通用批量 UPSERT Mapper
 * 使用 MySQL INSERT ON DUPLICATE KEY UPDATE 语法
 */
public interface UpsertMapper<T> {

    /**
     * 批量UPSERT
     *
     * @param list          实体列表
     * @param tableName     表名
     * @param uniqueColumns 唯一约束列（MySQL ON DUPLICATE KEY 自动根据唯一索引处理，此参数可忽略或作注释）
     * @param columns       列名与属性名映射列表
     * @return 影响行数
     */
    int upsertBatch(@Param("list") List<T> list,
                    @Param("tableName") String tableName,
                    @Param("uniqueColumns") String uniqueColumns,
                    @Param("columns") List<BatchInsertServiceImpl.ColumnMapping> columns);
}

package com.renhe.di.store.service;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 批量插入服务接口
 * 提供高性能批量插入和批量插入或更新能力
 */
public interface BatchInsertService {

    /**
     * 批量插入（仅插入，性能最优）
     *
     * @param entities  实体列表
     * @param batchSize 每批大小
     * @param <T>       实体类型
     * @return 是否成功
     */
    <T> boolean batchInsert(List<T> entities, int batchSize);

    /**
     * 批量插入（默认每200条一批）
     */
    default <T> boolean batchInsert(List<T> entities) {
        return batchInsert(entities, 200);
    }

    /**
     * 批量插入或更新
     * 先按唯一键查询存在性，分批次执行 insert/update
     *
     * @param entities  实体列表
     * @param service   对应的Service
     * @param batchSize 每批大小
     * @param <T>       实体类型
     * @return 成功数量
     */
    <T> int batchInsertOrUpdate(List<T> entities, IService<T> service, int batchSize);

    /**
     * 批量插入或更新（默认每200条一批）
     */
    default <T> int batchInsertOrUpdate(List<T> entities, IService<T> service) {
        return batchInsertOrUpdate(entities, service, 200);
    }
}

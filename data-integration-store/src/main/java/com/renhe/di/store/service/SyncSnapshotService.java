package com.renhe.di.store.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.entity.DiSyncSnapshot;

import java.util.List;

/**
 * 同步快照服务
 */
public interface SyncSnapshotService extends IService<DiSyncSnapshot> {

    /**
     * 保存或更新同步快照（同一项目+数据类型每天只保留最新一条）
     *
     * @param sourceProjectNum 项目编号
     * @param dataType         数据类型
     * @param thirdPartyTotal  第三方总量
     * @param localTotal       本地总量
     */
    void saveSnapshot(String sourceProjectNum, String dataType, int thirdPartyTotal, int localTotal);

    /**
     * 查询指定项目的最新同步快照列表
     *
     * @param sourceProjectNum 项目编号
     * @return 快照列表
     */
    List<DiSyncSnapshot> getLatestByProject(String sourceProjectNum);

    /**
     * 查询指定项目+数据类型+月份的最新快照
     *
     * @param sourceProjectNum 项目编号
     * @param dataType         数据类型
     * @param monthId          月份标识（yyyy-MM）
     * @return 月份快照，不存在时返回 null
     */
    DiSyncSnapshot getMonthSnapshot(String sourceProjectNum, String dataType, String monthId);

    /**
     * 保存或更新月份级快照
     *
     * @param sourceProjectNum    项目编号
     * @param dataType            数据类型
     * @param monthId             月份标识（yyyy-MM）
     * @param monthSyncDate       该月份已同步到的最早考勤时间
     * @param monthThirdPartyTotal 该月份第三方数据总量
     */
    void saveMonthSnapshot(String sourceProjectNum, String dataType, String monthId,
                           java.time.LocalDateTime monthSyncDate, int monthThirdPartyTotal);

    /**
     * 保存页码级采集检查点（用于断点续传）
     * 每页采集成功后调用，记录已采集到的最后一页
     *
     * @param sourceProjectNum    项目编号
     * @param dataType            数据类型
     * @param monthId             月份标识
     * @param lastCollectedPage   已成功采集的最后一页页码（1-based）
     * @param monthTotalPages     该月份总页数
     * @param monthThirdPartyTotal 该月份第三方数据总量
     */
    void savePageCheckpoint(String sourceProjectNum, String dataType, String monthId,
                           int lastCollectedPage, int monthTotalPages, int monthThirdPartyTotal);

    /**
     * 标记月份采集完成（所有页均已采集入库）
     *
     * @param sourceProjectNum    项目编号
     * @param dataType            数据类型
     * @param monthId             月份标识
     * @param monthThirdPartyTotal 该月份第三方数据总量
     */
    void markMonthComplete(String sourceProjectNum, String dataType, String monthId,
                           int monthThirdPartyTotal);

    /**
     * 查询指定项目+数据类型的最新全局快照（monthId 为 null）
     *
     * @param sourceProjectNum 项目编号
     * @param dataType         数据类型
     * @return 最新全局快照，不存在时返回 null
     */
    DiSyncSnapshot getLatestSnapshot(String sourceProjectNum, String dataType);
}

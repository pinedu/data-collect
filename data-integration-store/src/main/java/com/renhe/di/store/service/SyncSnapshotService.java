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
}

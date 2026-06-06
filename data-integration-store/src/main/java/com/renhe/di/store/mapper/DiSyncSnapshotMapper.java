package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.DiSyncSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 同步快照 Mapper
 */
@Mapper
public interface DiSyncSnapshotMapper extends BaseMapper<DiSyncSnapshot> {

    /**
     * 查询指定项目每个数据类型的最新快照
     */
    @Select("SELECT s.* FROM di_sync_snapshot s " +
            "INNER JOIN (SELECT source_project_num, data_type, MAX(sync_time) AS max_time " +
            "            FROM di_sync_snapshot " +
            "            WHERE source_project_num = #{sourceProjectNum} AND deleted = 0 " +
            "            GROUP BY source_project_num, data_type) t " +
            "ON s.source_project_num = t.source_project_num " +
            "AND s.data_type = t.data_type " +
            "AND s.sync_time = t.max_time " +
            "WHERE s.deleted = 0")
    List<DiSyncSnapshot> selectLatestByProject(@Param("sourceProjectNum") String sourceProjectNum);
}

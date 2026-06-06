package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.DiSyncLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 同步日志Mapper
 */
@Mapper
public interface DiSyncLogMapper extends BaseMapper<DiSyncLog> {

    /**
     * 查询指定项目和数据类型的最后成功同步时间
     */
    @Select("SELECT MAX(end_time) FROM di_sync_log " +
            "WHERE data_type = #{dataType} " +
            "AND source_project_num = #{projectNum} " +
            "AND status = 'SUCCESS'")
    LocalDateTime selectLastSyncTime(@Param("dataType") String dataType, @Param("projectNum") String projectNum);
}

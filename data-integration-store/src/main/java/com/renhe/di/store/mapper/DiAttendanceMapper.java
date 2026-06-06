package com.renhe.di.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.renhe.di.store.entity.DiAttendance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 考勤记录Mapper
 */
@Mapper
public interface DiAttendanceMapper extends BaseMapper<DiAttendance> {

    /**
     * 查询项目最后一条考勤记录时间
     */
    @Select("SELECT MAX(attendance_time) FROM di_attendance " +
            "WHERE source_project_num = #{projectNum} " +
            "AND deleted = 0")
    LocalDateTime selectLastAttendanceTime(@Param("projectNum") String projectNum);
}

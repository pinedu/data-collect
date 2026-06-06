package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.UpsertKeys;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 考勤记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_attendance")
@UpsertKeys({"source_project_num", "attendance_id"})
public class DiAttendance extends SyncDataEntity {

    /** 考勤记录 ID */
    private String attendanceId;

    /** 人员 ID */
    private String personId;

    /** 人员姓名 */
    private String personName;

    /** 班组 ID */
    private String teamId;

    /** 班组名称 */
    private String teamName;

    /** 考勤时间 */
    private LocalDateTime attendanceTime;

    /** 考勤方向（上班/下班） */
    private String attendanceDirection;

    /** 考勤方式（人脸识别/打卡等） */
    private String attendanceWay;

    /** 考勤地点 */
    private String attendanceAddress;

    /** 考勤照片 URL */
    private String attendanceUrl;

    /** 工作状态 */
    private String jobStatus;

    /** 项目名称（非数据库字段，关联查询填充） */
    @TableField(exist = false)
    private String projectName;
}

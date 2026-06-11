package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目工资考勤统计明细表
 */
@Data
@TableName("di_project_salary_attendance_detail")
public class DiProjectSalaryAttendanceDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源项目编号 */
    private String sourceProjectNum;

    /** 统计月份（格式：YYYY-MM） */
    private String dateMonth;

    /** 班组名称 */
    private String teamName;

    /** 人员姓名 */
    private String personName;

    /** 考勤天数 */
    private Integer attDayNum;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

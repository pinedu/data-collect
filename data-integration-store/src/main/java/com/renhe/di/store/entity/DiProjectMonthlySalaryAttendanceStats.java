package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.GeneratedColumn;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目月份工资考勤统计表
 */
@Data
@TableName("di_project_monthly_salary_attendance_stats")
public class DiProjectMonthlySalaryAttendanceStats {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源项目编号 */
    private String sourceProjectNum;

    /** 项目名称 */
    private String projectName;

    /** 年份 */
    private String whichYear;

    /** 月份 */
    private String whichMonth;

    /** 发放形式 */
    private String payType;

    /** 发放金额 */
    private BigDecimal payAmount;

    /** 发放人数 */
    private Integer payPersonNum;

    /** 考勤次数 */
    private Integer attendTimes;

    /** 考勤人数 */
    private Integer attendPersonNum;

    /** 有工资有考勤人数 */
    private BigDecimal salaryAttendNum;

    /** 有考勤无工资人数 */
    private BigDecimal attendNoSalaryNum;

    /** 有工资无考勤人数 */
    private BigDecimal salaryNoAttendNum;

    /** 统计月份（YYYY-MM，MySQL生成列） */
    @GeneratedColumn
    private String statMonth;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

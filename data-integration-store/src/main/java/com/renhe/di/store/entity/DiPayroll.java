package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.UpsertKeys;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工资信息主表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_payroll")
@UpsertKeys({"source_project_num", "salary_id"})
public class DiPayroll extends SyncDataEntity {

    /** 工资批次 ID */
    private String salaryId;

    /** 工资所属月份（格式：yyyy-MM） */
    private String salaryMonth;

    /** 工资总金额 */
    private BigDecimal totalAmount;

    /** 涉及人数 */
    private Integer personCount;

    /** 发放状态 */
    private String payStatus;

    /** 提交编号（第三方平台） */
    private String submitNo;

    /** 项目名称（非数据库字段，关联查询填充） */
    @TableField(exist = false)
    private String projectName;
}

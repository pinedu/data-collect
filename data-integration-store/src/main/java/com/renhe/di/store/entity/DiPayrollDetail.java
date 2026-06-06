package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.UpsertKeys;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 工资明细实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_payroll_detail")
@UpsertKeys({"salary_id", "detail_id"})
public class DiPayrollDetail extends SyncDataEntity {

    /** 工资批次 ID（关联 DiPayroll） */
    private String salaryId;

    /** 明细记录 ID */
    private String detailId;

    /** 人员 ID */
    private String personId;

    /** 人员姓名 */
    private String personName;

    /** 银行卡号 */
    private String bankCardNo;

    /** 应发金额 */
    private BigDecimal payableAmount;

    /** 实发金额 */
    private BigDecimal realAmount;

    /** 发放日期 */
    private LocalDate payDate;

    /** 发放方式（银行卡/现金等） */
    private String payMethod;

    /** 发放状态 */
    private String payStatus;

}

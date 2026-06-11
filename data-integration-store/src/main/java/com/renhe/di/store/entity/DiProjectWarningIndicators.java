package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目6个百分百预警指标表
 */
@Data
@TableName("di_project_warning_indicators")
public class DiProjectWarningIndicators {

    /** 项目编号（主键） */
    @TableId
    private String sourceProjectNum;

    /** 项目名称 */
    private String projectName;

    /** 统计月份 */
    private String dateMonth;

    /** 是否存储工资保证金（是/否） */
    private String isStoreWageDeposit;

    /** 工资保证金存储说明/失败原因 */
    private String isStoreWageDepositFailReason;

    /** 保证金类型（银行保函等） */
    private String depositType;

    /** 存储金额（万元） */
    private BigDecimal depositAmount;

    /** 保证金有效期 */
    private String depositValidity;

    /** 保证金状态 */
    private String depositStatus;

    /** 保证金失败原因 */
    private String depositFailReason;

    /** 保证金凭证图片路径 */
    private String depositImgs;

    /** 是否签订劳动合同（是/否） */
    private String isSignContract;

    /** 劳动合同签署说明/失败原因 */
    private String isSignContractFailReason;

    /** 合同签订人数 */
    private Integer signContractNum;

    /** 电子合同签订数 */
    private Integer signElectronicContractNum;

    /** 在职人数 */
    private Integer onJobNum;

    /** 施工合同状态 */
    private String constructionContractStatus;

    /** 施工合同URL */
    private String constructionContractUrl;

    /** 是否实名制考勤（是/否） */
    private String isRealNameAttendance;

    /** 实名制考勤说明/失败原因 */
    private String isRealNameAttendanceFailReason;

    /** 当日考勤人数 */
    private Integer currentDayAttendPersonNum;

    /** 在岗人数 */
    private Integer onDutyPersonNum;

    /** 发放工资且有考勤人数 */
    private Integer payAndAttendNum;

    /** 上上月考勤人数 */
    private Integer lastOfLastMonthAttendNum;

    /** 无考勤人数 */
    private Integer noAttendNum;

    /** 无合同考勤人数 */
    private Integer noContractAttendNum;

    /** 是否分账拨付（是/否） */
    private String isSplitAppropriation;

    /** 分账拨付说明/失败原因 */
    private String isSplitAppropriationFailReason;

    /** 拨付失败原因 */
    private String appropriationFailReason;

    /** 银行代发状态（是/否/预警） */
    private String isAgentPayment;

    /** 银行代发说明/失败原因 */
    private String isAgentPaymentFailReason;

    /** 上月发放工资人数 */
    private Integer lastMonthPayNum;

    /** 上月发放人均工资 */
    private BigDecimal avgSalaryAmount;

    /** 发薪日期 */
    private String salaryDay;

    /** 单人单月工资发放超过5万元人数 */
    private Integer overOneHundredThousandNum;

    /** 单人单月工资发放超过30万元人数 */
    private Integer overThreeHundredThousandNum;

    /** 是否离场结算（是/否） */
    private String isExitSettlement;

    /** 离场结算说明/失败原因 */
    private String isExitSettlementFailReason;

    /** 离场结算人员数 */
    private Integer exitWithSettlementNum;

    /** 离场未结算人员数 */
    private Integer exitWithNoSettlementNum;

    /** 是否开设专户（是/否） */
    private String isOpenSpecialAccount;

    /** 专户开设说明/失败原因 */
    private String isOpenSpecialAccountFailReason;

    /** 开户账号 */
    private String accountNum;

    /** 专户余额 */
    private BigDecimal accountBalance;

    /** 账户收款金额 */
    private BigDecimal accountReceiptsAmount;

    /** 结算金额 */
    private BigDecimal settlementAmount;

    /** 账户信息更新时间 */
    private LocalDateTime accountEditTime;

    /** 账户验证状态 */
    private String accountVerifyStatus;

    /** 账户验证失败原因 */
    private String accountVerifyFailReason;

    /** 账户三方协议URL */
    private String accountTripartiteAgreement;

    /** 维权告示牌URL */
    private String indicatorBoardUrl;

    /** 投诉数量 */
    private Integer complaintNum;

    /** 异议数量 */
    private Integer objectionNums;

    /** 项目创建时间 */
    private LocalDateTime projectCreateTime;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;
}

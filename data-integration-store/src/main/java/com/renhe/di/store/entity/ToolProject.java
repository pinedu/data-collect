package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 数据报告-项目信息表（tool_project）
 */
@Data
@TableName("tool_project")
public class ToolProject {

    /** 项目编号 */
    private String projectNum;

    /** 项目名称 */
    private String projectName;

    /** 项目介绍 */
    private String projectIntroduction;

    /** 项目备案编号 */
    private String recordNumber;

    /** 项目所在区域 */
    private String areaName;

    /** 项目所在区域编码 */
    private String areaCode;

    /** 项目详细地址 */
    private String projectDetailedAddress;

    /** 经度 */
    private String lon;

    /** 纬度 */
    private String lat;

    /** 劳资专员姓名 */
    private String laborName;

    /** 劳资专员身份证号码 */
    private String laborCard;

    /** 劳资专员联系电话 */
    private String laborPhone;

    /** 项目经理姓名 */
    private String projectManagerName;

    /** 项目经理身份证号码 */
    private String projectManagerCard;

    /** 项目经理联系电话 */
    private String projectManagerPhone;

    /** 项目状态 */
    private String projectStatus;

    /** 合同编号 */
    private String contractNo;

    /** 开工日期 */
    private String commencementDate;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注信息 */
    private String remark;

    /** 新莫愁系统项目编号 */
    private String xmcProjectNum;

    /** 工程类别 */
    private String worksCategory;

    /** 所属主管部门 */
    private String competentDepartment;

    /** 所属二级主管部门 */
    private String secondaryCompetentDepartment;

    /** 项目运营人员 */
    private String operationsStaff;

    /** 施工单位 */
    private String constructionUnit;

    /** 项目施工总造价（万元） */
    private BigDecimal totalConstructionCost;

    /** 项目审定工程产值（万元） */
    private BigDecimal approvedEngineeringOutputValue;

    /** 项目当前累计报审进度产值（万元） */
    private BigDecimal approvalProgressOutputValue;

    /** 服务开始日期 */
    private String serviceStartDate;

    /** 服务结束日期 */
    private String serviceEndDate;

    /** 运营人员联系电话 */
    private String operationsStaffPhone;

    /** 授权书附件url */
    private String authorizationAttachmentUrl;

    /** 合同约定拨付率阈值 */
    private String disbursementRateThreshold;
}

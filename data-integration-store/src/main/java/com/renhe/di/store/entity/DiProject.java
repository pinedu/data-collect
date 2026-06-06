package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.UpsertKeys;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目信息实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_project")
@UpsertKeys("id")
public class DiProject extends SyncDataEntity {

    /** 项目编号（本平台） */
    private String projectNum;

    /** 项目名称 */
    private String projectName;

    /** 施工许可证号/备案编号 */
    private String recordNumber;

    /** 行政区划代码 */
    private String areaCode;

    /** 项目状态 */
    private String projectStatus;

    /** 经度 */
    private BigDecimal lon;

    /** 纬度 */
    private BigDecimal lat;

    /** 开工日期 */
    private LocalDate commencementDate;

}

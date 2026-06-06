package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 参建单位实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_contractor")
public class DiContractor extends SyncDataEntity {

    /** 参建单位 ID */
    private String contractorId;

    /** 企业名称 */
    private String companyName;

    /** 企业类型（施工单位/监理单位等） */
    private String companyType;

    /** 统一社会信用代码 */
    private String creditCode;

    /** 负责人姓名 */
    private String managerName;

    /** 负责人联系电话 */
    private String managerPhone;

}

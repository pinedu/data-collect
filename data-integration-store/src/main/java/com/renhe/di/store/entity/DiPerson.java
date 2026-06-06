package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.UpsertKeys;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 人员信息实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_person")
@UpsertKeys({"source_project_num", "person_id"})
public class DiPerson extends SyncDataEntity {

    /** 人员 ID */
    private String personId;

    /** 人员姓名 */
    private String personName;

    /** 身份证号 */
    private String idCardNo;

    /** 联系电话 */
    private String phone;

    /** 所属班组 ID */
    private String teamId;

    /** 所属班组名称 */
    private String teamName;

    /** 工种（如钢筋工、木工等） */
    private String workType;

    /** 工作状态（在职/离职） */
    private String jobStatus;

    /** 人脸照片 URL */
    private String faceUrl;

    /** 银行卡号 */
    private String bankCardNo;

    /** 开户行名称 */
    private String bankName;

    /** 登记入场时间 */
    private LocalDate registerTime;

    /** 离场时间 */
    private LocalDate quitTime;

    /** 项目名称（非数据库字段，关联查询填充） */
    @TableField(exist = false)
    private String projectName;
}

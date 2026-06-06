package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目配置实体（对接第三方平台所需的项目账号配置）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_project_config")
public class DiProjectConfig extends BaseEntity {

    private String id;

    /**
     * 项目编号（本平台）
     */
    private String projectNum;

    /**
     * 源平台项目编号（第三方）
     */
    private String sourceProjectNum;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 第三方平台账号
     */
    private String account;

    /**
     * 第三方平台密码
     */
    private String password;

    /**
     * 平台编号（1=黔薪保）
     */
    private Integer platNum;

    /**
     * 项目状态（1=启用 0=禁用）
     */
    private Integer status;

    /**
     * 项目实际开工日期
     */
    private LocalDate actualBeginDate;

    /**
     * 最后同步时间
     */
    private LocalDateTime lastSyncTime;

    /**
     * 备注
     */
    private String remark;
}

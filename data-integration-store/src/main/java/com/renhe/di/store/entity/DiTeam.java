package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.renhe.di.store.annotation.GeneratedColumn;
import com.renhe.di.store.annotation.UpsertKeys;
import com.renhe.di.store.handler.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 班组信息实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("di_team")
@UpsertKeys({"source_project_num", "team_id"})
public class DiTeam extends SyncDataEntity {

    /** 班组 ID */
    private String teamId;

    /** 班组名称 */
    private String teamName;

    /** 所属参建单位 ID（MySQL 生成列，由 ext_data 自动提取，INSERT 时跳过） */
    @GeneratedColumn
    private String contractorId;

    /** 班组长姓名 */
    private String leaderName;

    /** 班组长身份证号 */
    private String leaderIdcard;

    /** 班组长联系电话 */
    private String leaderPhone;

    /** 工种（如钢筋工、木工等） */
    private String workType;

    /** 班组状态 */
    private String teamStatus;

    /** 进场日期 */
    private LocalDate approachDate;

    /** 离场日期 */
    private LocalDate departureDate;

    /** 项目名称（非数据库字段，关联查询填充） */
    @TableField(exist = false)
    private String projectName;

    // extData 继承自 SyncDataEntity，此处不再重复声明
}

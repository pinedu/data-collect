package com.renhe.di.store.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.renhe.di.store.handler.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 同步数据基础实体（所有同步数据表共用字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SyncDataEntity extends BaseEntity {

    /** 主键 ID */
    @TableId
    private String id;

    /** 源平台项目编号（第三方） */
    private String sourceProjectNum;

    /** 扩展数据（JSONB 格式，存储平台差异化字段） */
    @TableField(value = "ext_data", typeHandler = JsonbTypeHandler.class)
    private cn.hutool.json.JSONObject extData;

    /** 数据版本号（用于增量同步判断） */
    private Integer dataVersion;

    /** 同步类型（FULL=全量，INCREMENTAL=增量） */
    private String syncType;

    /** 最后同步时间 */
    private LocalDateTime syncTime;
}

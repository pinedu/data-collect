package com.renhe.di.store.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体类的 UPSERT 唯一约束列
 * 用于 MySQL INSERT ON DUPLICATE KEY UPDATE 逻辑（仅作标识，MySQL主要依赖DB层唯一索引）
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UpsertKeys {
    /**
     * 唯一约束列名数组
     * 例如：{"source_project_num", "team_id"}
     */
    String[] value();
}

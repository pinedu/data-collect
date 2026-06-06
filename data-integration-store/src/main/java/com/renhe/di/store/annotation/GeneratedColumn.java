package com.renhe.di.store.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 MySQL 生成列（Generated Column / Virtual Column）
 * 被标记的字段在 INSERT/UPDATE 时会被自动跳过，由数据库自动计算生成
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedColumn {
}

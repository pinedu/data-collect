package com.renhe.di.store.service.impl;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.IService;
import com.renhe.di.store.annotation.GeneratedColumn;
import com.renhe.di.store.annotation.UpsertKeys;
import com.renhe.di.store.mapper.UpsertMapper;
import com.renhe.di.store.service.BatchInsertService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 批量插入服务实现
 * 使用 MySQL INSERT ON DUPLICATE KEY UPDATE 实现通用批量 UPSERT
 */
@Slf4j
@Service
public class BatchInsertServiceImpl implements BatchInsertService {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Override
    public <T> boolean batchInsert(List<T> entities, int batchSize) {
        if (entities == null || entities.isEmpty()) {
            return true;
        }
        log.warn("batchInsert需要通过具体Service调用saveBatch，请使用batchInsertOrUpdate");
        return false;
    }

    @Override
    public <T> int batchInsertOrUpdate(List<T> entities, IService<T> service, int batchSize) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        int totalSize = entities.size();
        log.info("开始批量UPSERT，数据量：{}，批次大小：{}", totalSize, batchSize);

        int successCount = 0;
        int batchNum = 0;

        for (int i = 0; i < totalSize; i += batchSize) {
            batchNum++;
            List<T> batch = entities.subList(i, Math.min(i + batchSize, totalSize));
            try {
                int rows = doUpsertBatch(batch);
                successCount += batch.size();
                log.debug("第{}批UPSERT成功，影响行数：{}", batchNum, rows);
            } catch (Exception e) {
                log.error("第{}批UPSERT异常（本批{}条数据全部失败）", batchNum, batch.size(), e);
            }
        }

        log.info("批量UPSERT完成，总计：{}，成功：{}", totalSize, successCount);
        return successCount;
    }

    /**
     * 执行批量 UPSERT（MySQL INSERT ON DUPLICATE KEY UPDATE）
     */
    private <T> int doUpsertBatch(List<T> batch) {
        if (batch.isEmpty()) return 0;

        Class<?> entityClass = batch.get(0).getClass();

        // 0. 自动填充主键 ID（原始 SQL 绕过了 MyBatis-Plus 的 @TableId 自动生成）
        ensureIdsPopulated(batch, entityClass);

        // 1. 获取表名
        String tableName = getTableName(entityClass);

        // 2. 获取唯一约束列
        String uniqueColumns = getUniqueColumns(entityClass);

        // 3. 获取所有插入列（包含列名和属性名映射）
        List<ColumnMapping> columns = getColumnMappings(entityClass);

        // 4. 获取 UpsertMapper 并执行（try-with-resources 确保连接归还）
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UpsertMapper<T> mapper = sqlSession.getMapper(UpsertMapper.class);
            return mapper.upsertBatch(batch, tableName, uniqueColumns, columns);
        }
    }

    /**
     * 确保实体主键 ID 不为 null。
     * <p>
     * 原始 SQL 绕过了 MyBatis-Plus 的 @TableId 自动 ID 生成，
     * 如果 id 为 null，INSERT 会因主键 NOT NULL 约束而整批失败。
     * 这里用雪花算法补全缺失的 ID。
     */
    private <T> void ensureIdsPopulated(List<T> batch, Class<?> entityClass) {
        Field idField = findIdField(entityClass);
        if (idField == null) {
            return; // 无 @TableId 字段，跳过
        }
        idField.setAccessible(true);
        for (T entity : batch) {
            try {
                Object currentId = idField.get(entity);
                if (currentId == null || (currentId instanceof String && ((String) currentId).isEmpty())) {
                    idField.set(entity, IdWorker.getIdStr());
                }
            } catch (IllegalAccessException e) {
                log.warn("无法设置实体主键ID: {}", e.getMessage());
            }
        }
    }

    /**
     * 在类继承链中查找带 @TableId 注解的字段
     */
    private Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(com.baomidou.mybatisplus.annotation.TableId.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 从 @TableName 注解获取表名
     */
    private String getTableName(Class<?> clazz) {
        TableName tableName = clazz.getAnnotation(TableName.class);
        if (tableName != null && !tableName.value().isEmpty()) {
            return tableName.value();
        }
        // 默认驼峰转下划线
        return camelToUnderscore(clazz.getSimpleName());
    }

    /**
     * 从 @UpsertKeys 注解获取唯一约束列（逗号分隔）
     */
    private String getUniqueColumns(Class<?> clazz) {
        UpsertKeys upsertKeys = clazz.getAnnotation(UpsertKeys.class);
        if (upsertKeys != null && upsertKeys.value().length > 0) {
            return String.join(",", upsertKeys.value());
        }
        throw new IllegalArgumentException("实体类 " + clazz.getName() + " 缺少 @UpsertKeys 注解");
    }

    /**
     * 列名与属性名映射
     */
    @Data
    public static class ColumnMapping {
        private String columnName;   // 数据库列名（下划线）
        private String propertyName; // Java属性名（驼峰）

        public ColumnMapping(String columnName, String propertyName) {
            this.columnName = columnName;
            this.propertyName = propertyName;
        }
    }

    /**
     * 获取实体所有字段的列名-属性名映射（排除非数据库字段和复杂类型）
     */
    private List<ColumnMapping> getColumnMappings(Class<?> clazz) {
        List<ColumnMapping> columns = new ArrayList<>();
        // 遍历当前类及父类字段
        List<Field> fields = getAllFields(clazz);
        for (Field field : fields) {
            // 跳过 MySQL 生成列（由数据库自动计算，不允许显式赋值）
            if (field.getAnnotation(GeneratedColumn.class) != null) {
                continue;
            }
            // 跳过 static 字段
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // 检查 @TableField(exist = false)
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.exist()) {
                continue;
            }
            // 跳过无 TypeHandler 的复杂类型（如 JSONObject）
            if (isUnsupportedType(field.getType())) {
                log.debug("跳过无默认TypeHandler的字段: {}.{} ({})", clazz.getSimpleName(), field.getName(), field.getType().getName());
                continue;
            }
            // 获取列名：优先 @TableField.value，否则驼峰转下划线
            String columnName;
            if (tableField != null && !tableField.value().isEmpty()) {
                columnName = tableField.value();
            } else {
                columnName = camelToUnderscore(field.getName());
            }
            columns.add(new ColumnMapping(columnName, field.getName()));
        }
        return columns;
    }

    /**
     * 判断类型是否不支持（无默认 TypeHandler）
     * 注意：带有 @TableField(typeHandler = ...) 的字段会被包含，不在这里判断
     */
    private boolean isUnsupportedType(Class<?> type) {
        // 基础类型及其包装类都支持
        if (type.isPrimitive() ||
            type == String.class ||
            type == Integer.class || type == Long.class ||
            type == Double.class || type == Float.class ||
            type == Boolean.class || type == Short.class ||
            type == Byte.class || type == Character.class ||
            type == java.math.BigDecimal.class ||
            type == java.time.LocalDate.class ||
            type == java.time.LocalDateTime.class ||
            type == java.time.LocalTime.class ||
            type == java.sql.Date.class ||
            type == java.sql.Timestamp.class ||
            type == java.util.Date.class) {
            return false;
        }
        // cn.hutool.json.JSONObject 有自定义 JsonbTypeHandler，也支持
        if (type.getName().equals("cn.hutool.json.JSONObject")) {
            return false;
        }
        // 其他类型视为不支持
        return true;
    }

    /**
     * 获取类及其父类的所有字段
     */
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    /**
     * 驼峰命名转下划线命名
     */
    private String camelToUnderscore(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

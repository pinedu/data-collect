package com.renhe.di.ai.tool;

import com.renhe.di.ai.service.SchemaDescriber;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 表结构查询工具 — Schema 懒加载
 * <p>
 * LLM 在生成 SQL 前，先调用此工具获取目标表的字段详情，
 * 避免 System Prompt 中携带全量 Schema 导致 token 浪费。
 */
@Component
public class DescribeTableTool {

    @Tool(description = "获取指定数据库表的字段详情（字段名、注释、安全级别）。生成SQL查询前必须先调用此工具确认表结构。")
    public String describeTable(
            @ToolParam(description = "数据库表名，例如 di_project、di_person、di_attendance、di_project_warning_indicators 等") String tableName) {
        return SchemaDescriber.buildTableDetail(tableName);
    }
}

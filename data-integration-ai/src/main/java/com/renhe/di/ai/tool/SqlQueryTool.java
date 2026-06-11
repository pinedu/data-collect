package com.renhe.di.ai.tool;

import com.renhe.di.ai.service.SchemaDescriber;
import com.renhe.di.ai.service.SchemaDescriber.FieldDesc;
import com.renhe.di.ai.service.SchemaDescriber.FieldLevel;
import com.renhe.di.ai.service.SchemaDescriber.TableDesc;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Text-to-SQL 兜底工具
 * <p>
 * LLM 生成 SQL 后调用此工具执行，执行前做安全校验。
 * 支持自动从 project_name 解析并注入 source_project_num。
 */
@Slf4j
@Component
public class SqlQueryTool extends BaseDataTool {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ProjectNameResolver projectNameResolver;

    // 危险关键字，包含任一个即拒绝
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE",
            "EXEC", "EXECUTE", "GRANT", "REVOKE", "RENAME"
    );

    // 全局敏感字段名，不允许出现在 SELECT 列表中
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>();

    static {
        for (Map.Entry<String, TableDesc> e : SchemaDescriber.getSchema().entrySet()) {
            for (FieldDesc f : e.getValue().fields()) {
                if (f.level() == FieldLevel.sensitive) {
                    SENSITIVE_FIELDS.add(f.column().toLowerCase());
                }
            }
        }
    }

    /**
     * 执行自然语言生成的 SQL 查询
     * <p>
     * LLM 根据数据库 Schema 生成 SQL，调用本工具执行。
     * 执行前做安全校验：禁止危险操作、禁止敏感字段、强制 LIMIT。
     * 如果 SQL 涉及项目数据但没有 source_project_num，会自动从 project_name 条件中解析并注入。
     */
    @Tool(description = "执行自然语言翻译生成的SQL查询。SQL必须只包含SELECT、必须包含LIMIT、不能包含敏感字段（身份证/手机/银行卡/工资明细等）。如果查询涉及项目数据，SQL中应有project_name条件，系统会自动解析为source_project_num")
    public String executeNaturalQuery(
            @ToolParam(description = "安全校验通过的SELECT SQL语句，涉及项目数据时应包含project_name条件") String sql) {

        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 语句为空";
        }

        // 检查并自动注入 source_project_num（如果 SQL 有 project_name 但没有 source_project_num）
        String processedSql = injectSourceProjectNum(sql);
        if (processedSql.startsWith("ERROR:")) {
            return processedSql.substring(6); // 返回错误信息
        }
        sql = processedSql;

        String upperSql = sql.toUpperCase().trim();

        // 1. 检查是否为 SELECT
        if (!upperSql.startsWith("SELECT")) {
            return "数据不满足安全规则: 只允许 SELECT 查询";
        }

        // 2. 检查危险关键字
        for (String keyword : BLOCKED_KEYWORDS) {
            if (upperSql.contains(keyword)) {
                return "数据不满足安全规则: 包含禁止操作 " + keyword;
            }
        }

        // 3. 检查敏感字段
        String lowerSql = sql.toLowerCase();
        for (String field : SENSITIVE_FIELDS) {
            // 检查字段名是否出现在 SELECT 到 FROM 之间
            int selectIdx = lowerSql.indexOf("select");
            int fromIdx = lowerSql.indexOf(" from ");
            if (selectIdx >= 0 && fromIdx > selectIdx) {
                String selectClause = lowerSql.substring(selectIdx, fromIdx);
                if (selectClause.contains(field)) {
                    return "数据不满足安全规则: 包含敏感字段 " + field;
                }
            }
        }

        // 4. 强制 LIMIT
        if (!lowerSql.contains("limit")) {
            sql = sql.trim();
            // 去掉末尾分号
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1);
            }
            sql = sql + " LIMIT " + MAX_ROWS;
        }

        // 5. 执行查询
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            // LIMIT 截断
            rows = limit(rows);

            if (rows.isEmpty()) {
                return "查询结果为空";
            }

            return buildReply(summarize(rows));
        } catch (Exception e) {
            return "查询执行失败: " + e.getMessage();
        }
    }

    /**
     * 检查 SQL 是否涉及项目数据，如果有 project_name 条件但没有 source_project_num，
     * 自动调用 ProjectNameResolver 解析并注入 source_project_num 到 SQL 中。
     * <p>
     * 返回处理后的 SQL，或以 "ERROR:" 开头返回错误信息。
     */
    private String injectSourceProjectNum(String sql) {
        String lowerSql = sql.toLowerCase();

        // 检查是否涉及项目相关表
        boolean involvesProjectData = lowerSql.contains("di_person") || lowerSql.contains("di_team")
                || lowerSql.contains("di_attendance") || lowerSql.contains("di_payroll");

        if (!involvesProjectData) {
            return sql; // 不涉及项目数据，直接返回
        }

        // 已经有 source_project_num，不需要注入
        if (lowerSql.contains("source_project_num")) {
            return sql;
        }

        // 从 SQL 中提取 project_name 条件值
        String projectName = extractProjectNameFromSql(sql);
        if (projectName == null) {
            return "ERROR:查询人员/班组/考勤/工资等数据时，SQL中必须包含 project_name 条件（如 project_name = 'XX项目'），系统会自动解析为 source_project_num。请补充项目名称后再试。";
        }

        // 解析项目名称
        ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
        if (!result.resolved()) {
            return "ERROR:" + result.message();
        }

        String exactName = result.exactName();

        // 查询 source_project_num
        String sourceProjectNum = querySourceProjectNum(exactName);
        if (sourceProjectNum == null) {
            return "ERROR:未找到项目【" + exactName + "】的 source_project_num";
        }

        // 将 source_project_num 条件注入 SQL（在 WHERE 后或末尾添加）
        String injectedSql = appendSourceProjectCondition(sql, sourceProjectNum);
        log.info("SQL 自动注入 source_project_num: projectName={}, sourceProjectNum={}, originalSql={}, injectedSql={}",
                exactName, sourceProjectNum, sql, injectedSql);

        return injectedSql;
    }

    /**
     * 从 SQL 中提取 project_name 的条件值
     */
    private String extractProjectNameFromSql(String sql) {
        String pattern = "project_name\\s*[=like]+\\s*['\"]([^'\"]+)['\"]";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(sql);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && !name.equals("%") && name.length() >= 2) {
                return name;
            }
        }
        return null;
    }

    /**
     * 根据精确项目名查询 source_project_num
     */
    private String querySourceProjectNum(String exactProjectName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT source_project_num FROM di_project WHERE project_name = ? LIMIT 1",
                    exactProjectName);
            if (!rows.isEmpty() && rows.get(0).get("source_project_num") != null) {
                return rows.get(0).get("source_project_num").toString();
            }
        } catch (Exception e) {
            log.warn("查询 source_project_num 失败: projectName={}, error={}", exactProjectName, e.getMessage());
        }
        return null;
    }

    /**
     * 将 source_project_num 条件追加到 SQL 中
     */
    private String appendSourceProjectCondition(String sql, String sourceProjectNum) {
        String lowerSql = sql.toLowerCase();
        String condition = " source_project_num = '" + sourceProjectNum + "'";

        if (lowerSql.contains(" where ")) {
            // 在 WHERE 后追加 AND 条件
            int whereIdx = lowerSql.indexOf(" where ");
            // 找到 WHERE 后面的位置
            String beforeWhere = sql.substring(0, whereIdx + 7);
            String afterWhere = sql.substring(whereIdx + 7);
            return beforeWhere + condition + " AND " + afterWhere;
        } else {
            // 没有 WHERE，在 ORDER BY 或 GROUP BY 或末尾前添加
            int orderIdx = lowerSql.indexOf(" order by ");
            int groupIdx = lowerSql.indexOf(" group by ");
            int limitIdx = lowerSql.indexOf(" limit ");

            int insertIdx = sql.length();
            if (limitIdx > 0) insertIdx = Math.min(insertIdx, limitIdx);
            if (orderIdx > 0) insertIdx = Math.min(insertIdx, orderIdx);
            if (groupIdx > 0) insertIdx = Math.min(insertIdx, groupIdx);

            String before = sql.substring(0, insertIdx);
            String after = sql.substring(insertIdx);
            return before + " WHERE" + condition + after;
        }
    }

    /**
     * 已废弃：原 checkProjectNameInSql 逻辑已被 injectSourceProjectNum 替代
     */
    @Deprecated
    private String checkProjectNameInSql(String sql) {
        return null;
    }
}

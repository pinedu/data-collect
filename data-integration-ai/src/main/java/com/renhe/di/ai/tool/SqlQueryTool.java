package com.renhe.di.ai.tool;

import com.renhe.di.ai.service.SchemaDescriber;
import com.renhe.di.ai.service.SchemaDescriber.FieldDesc;
import com.renhe.di.ai.service.SchemaDescriber.FieldLevel;
import com.renhe.di.ai.service.SchemaDescriber.TableDesc;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Text-to-SQL 兜底工具
 * <p>
 * LLM 生成 SQL 后调用此工具执行，执行前做安全校验。
 */
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
     * 如果 SQL 涉及项目数据但项目名称不明确，会要求用户提供具体项目名称。
     */
    @Tool(description = "执行自然语言翻译生成的SQL查询。SQL必须只包含SELECT、必须包含LIMIT、不能包含敏感字段（身份证/手机/银行卡/工资明细等）。涉及项目数据的查询必须指定精确的项目名称")
    public String executeNaturalQuery(
            @ToolParam(description = "安全校验通过的SELECT SQL语句") String sql) {

        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 语句为空";
        }

        // 检查 SQL 是否涉及项目查询但项目名称不明确
        String projectNameCheck = checkProjectNameInSql(sql);
        if (projectNameCheck != null) {
            return projectNameCheck;
        }

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
     * 检查 SQL 中是否包含模糊的项目名称条件
     * 如果用户查询涉及项目数据但没有明确指定项目名称，返回提示信息
     */
    private String checkProjectNameInSql(String sql) {
        String lowerSql = sql.toLowerCase();

        // 检查是否涉及项目相关表但没有项目过滤条件
        boolean involvesProjectData = lowerSql.contains("di_person") || lowerSql.contains("di_team")
                || lowerSql.contains("di_attendance") || lowerSql.contains("di_payroll");

        // 检查是否有 source_project_num 过滤条件
        boolean hasSourceProjectFilter = lowerSql.contains("source_project_num");

        // 如果涉及项目数据但没有 source_project_num 过滤，拒绝执行
        if (involvesProjectData && !hasSourceProjectFilter) {
            return "查询人员/班组/考勤/工资等数据时，必须通过 source_project_num 指定具体项目。请先用 listProjects 工具获取项目清单，确认精确的项目名称后再查询。";
        }

        // 检查 source_project_num 是否为模糊值
        if (hasSourceProjectFilter) {
            String pattern = "source_project_num\\s*[=like]+\\s*['\"]([^'\"]+)['\"]";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(sql);

            if (m.find()) {
                String sourceProjectNum = m.group(1).trim();
                // 检查是否为模糊值（如 '%' 或空）
                if (sourceProjectNum.isEmpty() || sourceProjectNum.equals("%")
                        || sourceProjectNum.matches("^%+$") || sourceProjectNum.length() < 2) {
                    return "查询项目数据时必须提供具体的 source_project_num，请先用 listProjects 工具获取项目清单确认精确的项目名称。";
                }
            } else {
                // 有 source_project_num 字段但没有明确的值（如 IS NULL、IN 子查询等）
                return "查询项目数据时必须提供具体的 source_project_num，请先用 listProjects 工具获取项目清单确认精确的项目名称。";
            }
        }

        // 兼容旧逻辑：检查 project_name 字段（如果 SQL 中使用了）
        if (lowerSql.contains("project_name")) {
            String pattern = "project_name\\s*[=like]+\\s*['\"]([^'\"]+)['\"]";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(sql);

            if (m.find()) {
                String projectName = m.group(1).trim();
                if (projectName.isEmpty() || projectName.equals("%")
                        || projectName.matches("^%+$") || projectName.length() < 2) {
                    return "查询项目数据时必须提供具体的项目名称，请告诉我要查询哪个项目。";
                }

                ProjectNameResolver.ResolveResult result = projectNameResolver.resolve(projectName);
                if (!result.resolved()) {
                    return result.message();
                }
            } else {
                return "查询项目数据时必须提供具体的项目名称，请告诉我要查询哪个项目。";
            }
        }

        return null;
    }
}

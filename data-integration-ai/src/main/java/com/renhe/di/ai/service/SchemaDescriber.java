package com.renhe.di.ai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 数据库 Schema 描述器
 * <p>
 * 为 Text-to-SQL 提供表结构、字段说明和安全标注。
 * safe     = 可暴露给用户
 * sensitive = 禁止出现在 SQL 的 SELECT 中
 */
public class SchemaDescriber {

    /**
     * 描述 字段归属
     */
    public enum FieldLevel {safe, sensitive}

    /**
     * 字段描述
     */
    public record FieldDesc(String column, String comment, FieldLevel level) {}

    /**
     * 表描述
     */
    public record TableDesc(String table, String comment, List<FieldDesc> fields) {}

    /**
     * 排除的表名集合（不暴露给 LLM），由 AiConfig 在启动时注入
     */
    private static final Set<String> EXCLUDED_TABLES = new CopyOnWriteArraySet<>();

    private static final Map<String, TableDesc> SCHEMA = new LinkedHashMap<>();

    static {
        SCHEMA.put("di_project", new TableDesc("di_project", "项目信息",
                List.of(
                        new FieldDesc("project_num", "项目编号", FieldLevel.safe),
                        new FieldDesc("project_name", "项目名称", FieldLevel.safe),
                        new FieldDesc("record_number", "备案号", FieldLevel.safe),
                        new FieldDesc("area_code", "区域编码", FieldLevel.safe),
                        new FieldDesc("project_status", "项目状态（在建/完工等）", FieldLevel.safe),
                        new FieldDesc("commencement_date", "开工日期", FieldLevel.safe),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));

        SCHEMA.put("di_person", new TableDesc("di_person", "人员信息",
                List.of(
                        new FieldDesc("person_id", "人员ID", FieldLevel.safe),
                        new FieldDesc("person_name", "姓名", FieldLevel.safe),
                        new FieldDesc("id_card_no", "身份证号", FieldLevel.sensitive),
                        new FieldDesc("phone", "手机号", FieldLevel.sensitive),
                        new FieldDesc("team_id", "班组ID", FieldLevel.safe),
                        new FieldDesc("team_name", "班组名称", FieldLevel.safe),
                        new FieldDesc("work_type", "工种", FieldLevel.safe),
                        new FieldDesc("job_status", "在岗状态", FieldLevel.safe),
                        new FieldDesc("bank_card_no", "银行卡号", FieldLevel.sensitive),
                        new FieldDesc("bank_name", "银行名称", FieldLevel.sensitive),
                        new FieldDesc("register_time", "入场时间", FieldLevel.safe),
                        new FieldDesc("quit_time", "离场时间", FieldLevel.safe),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));

        SCHEMA.put("di_team", new TableDesc("di_team", "班组信息",
                List.of(
                        new FieldDesc("team_id", "班组ID", FieldLevel.safe),
                        new FieldDesc("team_name", "班组名称", FieldLevel.safe),
                        new FieldDesc("contractor_id", "承包商ID", FieldLevel.safe),
                        new FieldDesc("leader_name", "班组长姓名", FieldLevel.safe),
                        new FieldDesc("leader_idcard", "班组长身份证", FieldLevel.sensitive),
                        new FieldDesc("leader_phone", "班组长电话", FieldLevel.sensitive),
                        new FieldDesc("work_type", "工种", FieldLevel.safe),
                        new FieldDesc("team_status", "班组状态", FieldLevel.safe),
                        new FieldDesc("approach_date", "入场日期", FieldLevel.safe),
                        new FieldDesc("departure_date", "离场日期", FieldLevel.safe),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));

        SCHEMA.put("di_attendance", new TableDesc("di_attendance", "考勤记录",
                List.of(
                        new FieldDesc("attendance_id", "考勤ID", FieldLevel.safe),
                        new FieldDesc("person_id", "人员ID", FieldLevel.safe),
                        new FieldDesc("person_name", "姓名", FieldLevel.safe),
                        new FieldDesc("team_id", "班组ID", FieldLevel.safe),
                        new FieldDesc("team_name", "班组名称", FieldLevel.safe),
                        new FieldDesc("attendance_time", "考勤时间", FieldLevel.safe),
                        new FieldDesc("attendance_direction", "进出方向（进场/出场）", FieldLevel.safe),
                        new FieldDesc("attendance_way", "考勤方式", FieldLevel.safe),
                        new FieldDesc("attendance_address", "考勤地址", FieldLevel.sensitive),
                        new FieldDesc("attendance_url", "考勤照片URL", FieldLevel.sensitive),
                        new FieldDesc("job_status", "在岗状态", FieldLevel.safe),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));

        SCHEMA.put("di_payroll", new TableDesc("di_payroll", "工资汇总",
                List.of(
                        new FieldDesc("payroll_id", "工资单ID", FieldLevel.safe),
                        new FieldDesc("team_id", "班组ID", FieldLevel.safe),
                        new FieldDesc("team_name", "班组名称", FieldLevel.safe),
                        new FieldDesc("total_amount", "应发总额", FieldLevel.safe),
                        new FieldDesc("actual_amount", "实发总额", FieldLevel.safe),
                        new FieldDesc("person_count", "人数", FieldLevel.safe),
                        new FieldDesc("payroll_month", "工资月份", FieldLevel.safe),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));

        SCHEMA.put("di_payroll_detail", new TableDesc("di_payroll_detail", "工资明细",
                List.of(
                        new FieldDesc("payroll_id", "工资单ID", FieldLevel.safe),
                        new FieldDesc("person_id", "人员ID", FieldLevel.safe),
                        new FieldDesc("person_name", "姓名", FieldLevel.safe),
                        new FieldDesc("gross_pay", "应发工资", FieldLevel.sensitive),
                        new FieldDesc("net_pay", "实发工资", FieldLevel.sensitive),
                        new FieldDesc("deduction", "扣款", FieldLevel.sensitive),
                        new FieldDesc("bank_card_no", "银行卡号", FieldLevel.sensitive),
                        new FieldDesc("source_project_num", "来源项目编号", FieldLevel.safe)
                )));
    }

    /**
     * 获取所有表描述（排除已注册的排除表）
     */
    public static Map<String, TableDesc> getSchema() {
        if (EXCLUDED_TABLES.isEmpty()) {
            return SCHEMA;
        }
        Map<String, TableDesc> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, TableDesc> e : SCHEMA.entrySet()) {
            if (!EXCLUDED_TABLES.contains(e.getKey().toLowerCase())) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        return filtered;
    }

    /**
     * 注册需要排除的表（不暴露给 Text-to-SQL LLM）
     * <p>
     * 由 AiConfig 在启动时调用，通常排除 spring_ai_chat_memory、flyway_schema_history 等内部表。
     */
    public static void excludeTable(String tableName) {
        if (tableName != null && !tableName.isBlank()) {
            EXCLUDED_TABLES.add(tableName.trim().toLowerCase());
        }
    }

    /**
     * 生成 Text-to-SQL Prompt 用的 Schema 文本
     */
    public static String buildSchemaPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是数据库表结构，请根据用户问题生成SQL查询语句。\n\n");

        for (TableDesc table : getSchema().values()) {
            sb.append("表名: ").append(table.table()).append(" (").append(table.comment()).append(")\n");
            sb.append("安全字段（可查询）:\n");
            for (FieldDesc f : table.fields()) {
                if (f.level() == FieldLevel.safe) {
                    sb.append("  - ").append(f.column()).append(": ").append(f.comment()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("表关系: di_project.source_project_num = di_person.source_project_num\n");
        sb.append("        di_person.team_id = di_team.team_id\n");
        sb.append("        di_person.person_id = di_attendance.person_id\n");
        sb.append("        di_team.team_id = di_payroll.team_id\n");
        sb.append("\nSQL规则:\n");
        sb.append("1. 只能 SELECT 安全字段，禁止包含敏感字段\n");
        sb.append("2. 必须加 LIMIT 20\n");
        sb.append("3. 只生成 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP\n");
        sb.append("4. 优先使用 COUNT/SUM/AVG 聚合查询\n");
        sb.append("5. 【强制】涉及项目数据查询时，必须先用 listProjects 工具获取项目清单，确认精确的项目名称和 source_project_num 后再生成 SQL\n");
        sb.append("6. 【强制】禁止在 SQL 中直接硬编码用户输入的项目名称，必须通过工具解析后的精确名称查询\n");
        sb.append("7. 【强制】涉及人员/班组/考勤/工资等数据时，必须通过 source_project_num 关联项目，禁止全表查询\n");

        return sb.toString();
    }
}

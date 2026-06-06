package com.renhe.di.ai.tool;

import java.util.*;

/**
 * 数据工具基类 — 提供安全过滤、摘要、限长等通用能力
 * <p>
 * 所有业务工具继承此类，确保每条返回数据都经过安全处理。
 */
public abstract class BaseDataTool {

    // 最大返回行数
    protected static final int MAX_ROWS = 20;
    // 最大返回字符数
    protected static final int MAX_CHARS = 500;

    /**
     * 将 Map 列表按 whiteList 过滤，只保留白名单字段
     */
    protected List<Map<String, Object>> sanitize(List<Map<String, Object>> rows, Set<String> whiteList) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (String key : whiteList) {
                if (row.containsKey(key)) {
                    filtered.put(key, row.get(key));
                }
            }
            if (!filtered.isEmpty()) {
                result.add(filtered);
            }
        }
        return result;
    }

    /**
     * 强制 LIMIT，超过上限时截断
     */
    protected List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() <= MAX_ROWS) {
            return rows;
        }
        return rows.subList(0, MAX_ROWS);
    }

    /**
     * 多行转摘要文本
     * 超过 10 行时自动生成 "共 N 条，前 10 条为..." 格式
     */
    protected String summarize(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "查询结果为空";
        }

        int total = rows.size();
        List<Map<String, Object>> display = total > 10 ? rows.subList(0, 10) : rows;

        StringBuilder sb = new StringBuilder();
        if (total > 10) {
            sb.append("共 ").append(total).append(" 条，前 10 条为：\n");
        }

        // 取所有行的 key 并集作为表头
        Set<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> row : display) {
            headers.addAll(row.keySet());
        }

        for (Map<String, Object> row : display) {
            for (String key : headers) {
                Object val = row.get(key);
                if (val != null) {
                    sb.append(key).append("=").append(val).append(", ");
                }
            }
            // 去掉末尾逗号和空格
            int len = sb.length();
            if (len >= 2) {
                sb.setLength(len - 2);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建最终回复，确保不超过 MAX_CHARS 字
     */
    protected String buildReply(String content) {
        if (content == null || content.isEmpty()) {
            return "查询结果为空";
        }
        if (content.length() > MAX_CHARS) {
            return content.substring(0, MAX_CHARS - 3) + "...";
        }
        return content;
    }

    /**
     * 将单个 Map 行转为简洁的文本描述（用于聚合查询只有一行结果的场景）
     */
    protected String formatSingleRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "查询结果为空";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() != null) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append(", ");
            }
        }
        int len = sb.length();
        if (len >= 2) {
            sb.setLength(len - 2);
        }
        return sb.toString();
    }
}

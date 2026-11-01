package com.erp.common;

import java.util.regex.Pattern;

/**
 * SELECT-only 白名单静态工具（add-data-extract-console design D2 提取自
 * ReportTemplateServiceImpl D2，行为不变）：非空、单语句、SELECT 起头、
 * 词边界黑名单命中即 422。供 14.1 模板渲染/测试与 14.2 抽取执行器、
 * 质量勾稽 SQL 共用治理通道。
 */
public final class SqlGuard {

    /**
     * SELECT-only 词边界黑名单：命中即 422。含 DML/DDL/权限/文件/系统表特征。
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|REPLACE|DROP|ALTER|CREATE|TRUNCATE|RENAME|"
                    + "GRANT|REVOKE|CALL|EXECUTE|EXEC|PREPARE|DEALLOCATE|LOAD|OUTFILE|"
                    + "DUMPFILE|INFILE|INTO\\s+OUTFILE|FOR\\s+UPDATE|LOCK|UNLOCK|"
                    + "SET|USE|CALL|HANDLER|SHUTDOWN|KILL|FLUSH|RESET|PURGE|"
                    + "INSTALL|UNINSTALL|PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b",
            Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    /** D2 SQL 白名单：非空、单语句、SELECT 起头、黑名单词边界命中即 422 */
    public static void validate(String sqlText) {
        if (sqlText == null || sqlText.trim().isEmpty()) {
            throw new ServiceException(422, "查询 SQL 必填");
        }
        String s = sqlText.trim();
        String noTail = s.replaceAll(";\\s*$", "");
        if (noTail.contains(";")) {
            // 允许尾分号；中间分号 = 多语句
            throw new ServiceException(422, "仅允许单条 SQL 语句（禁多语句）");
        }
        if (!noTail.regionMatches(true, 0, "SELECT", 0, 6)
                && !noTail.startsWith("(")) {
            throw new ServiceException(422, "仅允许只读 SELECT 查询（以 SELECT 或子查询括号开头）");
        }
        if (FORBIDDEN.matcher(noTail).find()) {
            throw new ServiceException(422, "SQL 含非只读/危险关键字，仅允许只读 SELECT");
        }
    }
}

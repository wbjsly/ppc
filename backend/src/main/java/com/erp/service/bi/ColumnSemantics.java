package com.erp.service.bi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板列语义解析（add-adhoc-query-console design D1/D2，spec bi-adhoc-query 数据集字段语义）。
 * COLUMNS_JSON 双格式兼容：
 *   ① 存量纯列名：["COL_A","COL_B"] → role=DIMENSION、不可聚合
 *   ② 语义对象：[{"field":"COL_A","label":"区域","role":"DIMENSION|MEASURE",
 *                 "agg":["SUM","COUNT"],"timeFlag":true,"roleFlag":false}]
 * 纯静态工具，无 Spring 依赖。
 */
@Slf4j
public final class ColumnSemantics {

    /** 聚合白名单（spec：SUM/COUNT/AVG/MIN/MAX） */
    public static final List<String> AGG_WHITELIST = List.of("SUM", "COUNT", "AVG", "MIN", "MAX");

    public static final String ROLE_DIMENSION = "DIMENSION";
    public static final String ROLE_MEASURE = "MEASURE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ColumnSemantics() {
    }

    /** 解析出的单列语义 */
    public static final class Col {
        public String field;
        public String label;
        public String role;
        public List<String> agg;
        public boolean timeFlag;
        public boolean roleFlag;
        /** 行级实体过滤列（14.1 entityCol 语义，行级注入用） */
        public boolean entityCol;

        public boolean isMeasure() {
            return ROLE_MEASURE.equals(role);
        }

        /** 是否可按指定聚合方式使用（维度列恒不可聚合） */
        public boolean aggAllowed(String aggFun) {
            if (!isMeasure() || aggFun == null) {
                return false;
            }
            List<String> allowed = (agg == null || agg.isEmpty()) ? AGG_WHITELIST : agg;
            return allowed.contains(aggFun.toUpperCase());
        }

        /** 该列可作时间过滤判定（timeFlag 语义） */
        public boolean isTimeSemantic() {
            return timeFlag;
        }

        /** 该列可作组织过滤判定（roleFlag 语义） */
        public boolean isOrgSemantic() {
            return roleFlag;
        }
    }

    /**
     * 双格式兼容解析。入参为 COLUMNS_JSON 文本；解析失败返回空清单（消费方按无字段处理）。
     */
    public static List<Col> parse(String columnsJson) {
        List<Col> out = new ArrayList<>();
        if (columnsJson == null || columnsJson.isBlank()) {
            return out;
        }
        try {
            List<Object> arr = JSON.readValue(columnsJson, new TypeReference<List<Object>>() { });
            for (Object item : arr) {
                if (item instanceof String s) {
                    // 格式①：纯列名 → DIMENSION 不可聚合
                    Col c = new Col();
                    c.field = s;
                    c.label = s;
                    c.role = ROLE_DIMENSION;
                    c.agg = List.of();
                    out.add(c);
                } else if (item instanceof Map<?, ?> m) {
                    // 格式②：语义对象
                    Col c = new Col();
                    c.field = str(m.get("field"));
                    c.label = str(m.get("label")) == null ? c.field : str(m.get("label"));
                    String role = str(m.get("role"));
                    c.role = ROLE_MEASURE.equals(role) ? ROLE_MEASURE : ROLE_DIMENSION;
                    c.agg = aggList(m.get("agg"));
                    c.timeFlag = bool(m.get("timeFlag"));
                    c.roleFlag = bool(m.get("roleFlag"));
                    c.entityCol = bool(m.get("entityCol"));
                    if (c.field != null && !c.field.isBlank()) {
                        out.add(c);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("COLUMNS_JSON 解析失败（按无字段处理）: {}", e.getMessage());
        }
        return out;
    }

    /** 按字段名取语义；不存在返回 null */
    public static Col field(List<Col> cols, String field) {
        if (cols == null || field == null) {
            return null;
        }
        for (Col c : cols) {
            if (field.equalsIgnoreCase(c.field)) {
                return c;
            }
        }
        return null;
    }

    /** 字段名清单（白名单校验用，大写规范化） */
    public static List<String> fieldNames(List<Col> cols) {
        List<String> out = new ArrayList<>();
        for (Col c : cols) {
            out.add(c.field.toUpperCase());
        }
        return out;
    }

    private static List<String> aggList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                String s = str(o);
                if (s != null && AGG_WHITELIST.contains(s.toUpperCase())) {
                    out.add(s.toUpperCase());
                }
            }
        } else if (v instanceof String s && AGG_WHITELIST.contains(s.toUpperCase())) {
            out.add(s.toUpperCase());
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** 便捷：语义列 → 前端字段树 JSON（datasets 端点消费） */
    public static Map<String, Object> toTreeNode(Col c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", c.field);
        m.put("label", c.label);
        m.put("role", c.role);
        m.put("agg", c.agg);
        m.put("timeFlag", c.timeFlag);
        m.put("roleFlag", c.roleFlag);
        return m;
    }
}

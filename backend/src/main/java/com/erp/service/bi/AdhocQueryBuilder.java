package com.erp.service.bi;

import com.erp.common.ServiceException;
import com.erp.common.SqlGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化查询构建器（add-adhoc-query-console design D2，spec bi-adhoc-query
 * 结构化查询构建与预检阻断）。
 * QUERY_JSON → 包裹模板 SQL 生成 SELECT：字段白名单、聚合白名单、运算符白名单、
 * 筛选值全参数化（? 占位），绝不拼接用户输入；纯静态工具可单测。
 */
@Slf4j
public final class AdhocQueryBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 运算符白名单 → 固定 SQL 片段（值一律占位） */
    private static final Map<String, String> OP_SQL = Map.ofEntries(
            Map.entry("EQ", "="), Map.entry("NE", "<>"),
            Map.entry("GT", ">"), Map.entry("GE", ">="),
            Map.entry("LT", "<"), Map.entry("LE", "<="),
            Map.entry("LIKE", "LIKE"), Map.entry("BETWEEN", "BETWEEN"));

    private AdhocQueryBuilder() {
    }

    /** 构建产物：SQL + 参数绑定清单 */
    public static final class Built {
        public String sql;
        public List<Object> params = new ArrayList<>();
        /** 是否含时间语义字段的显式条件（预检消费） */
        public boolean hasTimeFilter;
        /** 是否含组织语义字段的显式条件（预检消费） */
        public boolean hasOrgFilter;
    }

    /**
     * 从 QUERY_JSON 构建执行 SQL。
     *
     * @param templateSql 模板 SQL（子查询包裹，不改写本体）
     * @param columns     数据集列语义（白名单与语义判定）
     * @param queryJson   结构化查询定义 {dimensions,measures[{field,agg}],filters[{field,op,value}],limit}
     * @param maxLimit    上限（EXPORT_ROW_LIMIT+1，截断判定由消费方做）
     */
    public static Built build(String templateSql, List<ColumnSemantics.Col> columns,
                              String queryJson, int maxLimit) {
        if (templateSql == null || templateSql.isBlank()) {
            throw new ServiceException(422, "数据集模板 SQL 为空");
        }
        Map<String, Object> q;
        try {
            q = JSON.readValue(queryJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new ServiceException(422, "查询定义 JSON 非法");
        }

        Built out = new Built();
        List<String> selectParts = new ArrayList<>();
        List<String> groupParts = new ArrayList<>();

        // 1) 维度列（白名单 + 标识符合规）
        List<String> dims = strList(q.get("dimensions"));
        for (String d : dims) {
            String col = ident(d, columns, "维度");
            selectParts.add(col);
            groupParts.add(col);
        }

        // 2) 指标列（字段白名单 + 聚合白名单 + AS 别名 m0/m1…）
        List<Map<String, Object>> measures = mapList(q.get("measures"));
        int mi = 0;
        for (Map<String, Object> m : measures) {
            String field = str(m.get("field"));
            String agg = str(m.get("agg"));
            String col = ident(field, columns, "指标");
            ColumnSemantics.Col sem = ColumnSemantics.field(columns, field);
            if (sem != null && !sem.isMeasure()) {
                throw new ServiceException(422, "字段 " + field + " 非指标列，不可聚合");
            }
            String aggUp = (agg == null || agg.isBlank()) ? "SUM" : agg.toUpperCase();
            if (!ColumnSemantics.AGG_WHITELIST.contains(aggUp)) {
                throw new ServiceException(422, "聚合方式 " + aggUp + " 不在白名单");
            }
            if (sem != null && !sem.aggAllowed(aggUp)) {
                throw new ServiceException(422, "字段 " + field + " 不支持聚合 " + aggUp);
            }
            selectParts.add(aggUp + "(" + col + ") AS m" + mi);
            mi++;
        }
        if (selectParts.isEmpty()) {
            throw new ServiceException(422, "至少选择一个维度或指标");
        }

        // 3) 筛选条件（op 白名单 + 值参数化；语义标记供预检）
        StringBuilder where = new StringBuilder();
        List<Map<String, Object>> filters = mapList(q.get("filters"));
        for (Map<String, Object> f : filters) {
            String field = str(f.get("field"));
            String op = str(f.get("op"));
            String col = ident(field, columns, "筛选");
            String opSql = OP_SQL.get(op == null ? "" : op.toUpperCase());
            if (opSql == null) {
                throw new ServiceException(422, "运算符 " + op + " 不在白名单");
            }
            ColumnSemantics.Col sem = ColumnSemantics.field(columns, field);
            if (sem != null && sem.isTimeSemantic()) {
                out.hasTimeFilter = true;
            }
            if (sem != null && sem.isOrgSemantic()) {
                out.hasOrgFilter = true;
            }
            if (where.length() > 0) {
                where.append(" AND ");
            }
            if ("BETWEEN".equalsIgnoreCase(op)) {
                List<Object> range = values(f.get("value"));
                if (range.size() != 2) {
                    throw new ServiceException(422, "BETWEEN 需要两个边界值");
                }
                where.append(col).append(" BETWEEN ? AND ?");
                out.params.add(range.get(0));
                out.params.add(range.get(1));
            } else if ("LIKE".equalsIgnoreCase(op)) {
                where.append(col).append(" LIKE ?");
                Object v = single(f.get("value"));
                out.params.add(v == null ? null : String.valueOf(v));
            } else {
                where.append(col).append(' ').append(opSql).append(" ?");
                out.params.add(single(f.get("value")));
            }
        }

        // 4) LIMIT（min(请求, maxLimit)）
        int limit = maxLimit;
        Object lim = q.get("limit");
        if (lim instanceof Number n && n.intValue() > 0) {
            limit = Math.min(n.intValue(), maxLimit);
        }

        // 5) 组装：模板 SQL 作为子查询包裹（不改写本体）
        String inner = templateSql.replaceAll(";\\s*$", "").trim();
        StringBuilder sb = new StringBuilder("SELECT ").append(String.join(", ", selectParts))
                .append(" FROM (").append(inner).append(") __adhoc_src");
        if (where.length() > 0) {
            sb.append(" WHERE ").append(where);
        }
        if (!groupParts.isEmpty()) {
            sb.append(" GROUP BY ").append(String.join(", ", groupParts));
        }
        sb.append(" LIMIT ").append(limit);
        out.sql = sb.toString();

        // 6) 生成后过 SqlGuard（纵深防御：生成器恒合规，此处为兜底）
        SqlGuard.validate(out.sql);
        return out;
    }

    /** 标识符校验：仅允许 [A-Za-z0-9_]，且必须在数据集列白名单内 */
    private static String ident(String field, List<ColumnSemantics.Col> columns, String kind) {
        if (field == null || !field.matches("[A-Za-z0-9_]+")) {
            throw new ServiceException(422, kind + "字段名非法：" + field);
        }
        if (ColumnSemantics.field(columns, field) == null) {
            throw new ServiceException(422, kind + "字段 " + field + " 不在数据集列定义中");
        }
        return field;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object v) {
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static Object single(Object v) {
        if (v instanceof List<?> l) {
            return l.isEmpty() ? null : l.get(0);
        }
        return v;
    }

    private static List<Object> values(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            out.addAll(l);
        } else if (v != null) {
            out.add(v);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

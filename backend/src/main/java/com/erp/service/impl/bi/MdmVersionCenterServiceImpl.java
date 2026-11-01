package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemVersionDao;
import com.erp.entity.mdm.MdmItemVersion;
import com.erp.mdm.event.MdmEventBus;
import com.erp.service.bi.MdmVersionCenterService;
import com.erp.security.AuditLogWriter;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.7 通用版本中心（add-mdm-version-sync）。
 * bizType 白名单（13 类版本表映射，禁 SQL 拼接）；diff 按快照 JSON 键并集三态；
 * 回滚以源版本快照写回主表生成 V(N+1)（直接生效，审计+事件，偏差声明见 proposal）。
 */
@Slf4j
@Service
public class MdmVersionCenterServiceImpl implements MdmVersionCenterService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** bizType -> [版本表, 主表, 编码列, 名称列]（白名单，未知类型 422） */
    private static final Map<String, String[]> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("ITEM", new String[]{"erp_mdm_item_version", "erp_mdm_item", "ITEM_CODE", "ITEM_NAME"});
        TABLES.put("CUSTOMER_GROUP", new String[]{"erp_mdm_customer_group_version", "erp_mdm_customer_group", "CUSTOMER_CODE", "CUSTOMER_NAME"});
        TABLES.put("CUSTOMER_VIEW", new String[]{"erp_mdm_customer_view_version", "erp_mdm_customer_view", "GROUP_ID", "CONTACT_NAME"});
        TABLES.put("SUPPLIER", new String[]{"erp_mdm_supplier_version", "erp_mdm_supplier", "SUPPLIER_CODE", "SUPPLIER_NAME"});
        TABLES.put("EXCHANGE_RATE", new String[]{"erp_mdm_exchange_rate_version", "erp_mdm_exchange_rate", "BASE_CCY", "RATE_TYPE"});
        TABLES.put("TAX_CODE", new String[]{"erp_mdm_tax_code_version", "erp_mdm_tax_code", "TAX_CODE", "TAX_CODE"});
        TABLES.put("ORG_UNIT", new String[]{"erp_mdm_org_unit_version", "erp_mdm_org_unit", "OU_CODE", "OU_NAME"});
        TABLES.put("COST_CENTER", new String[]{"erp_mdm_cost_center_version", "erp_mdm_cost_center", "CC_CODE", "CC_NAME"});
        TABLES.put("PROFIT_CENTER", new String[]{"erp_mdm_profit_center_version", "erp_mdm_profit_center", "PC_CODE", "PC_NAME"});
        TABLES.put("LEGAL_ENTITY", new String[]{"erp_mdm_legal_entity_version", "erp_mdm_legal_entity", "LE_CODE", "LE_NAME"});
        TABLES.put("ITEM_CATEGORY", new String[]{"erp_mdm_item_category_version", "erp_mdm_item_category", "CATEGORY_CODE", "CATEGORY_NAME"});
        TABLES.put("PRICE_AGREEMENT", new String[]{"erp_mdm_price_agreement_version", "erp_mdm_price_agreement", "PA_CODE", "PA_NAME"});
        TABLES.put("TAX_POLICY", new String[]{"erp_mdm_tax_policy_version", "erp_mdm_tax_policy", "POLICY_NO", "POLICY_NAME"});
    }

    /** 回滚写回主表时跳过的审计/系统列 */
    private static final Set<String> SKIP_COLS = Set.of("ID", "CREATE_BY", "CREATE_DATE",
            "UPDATE_BY", "UPDATE_DATE", "DEL_FLAG", "VER_NO");

    private final JdbcTemplate jdbc;
    private final MdmItemVersionDao itemVersionDao;
    private final MdmEventBus eventBus;
    private final AuditLogWriter auditLog;

    public MdmVersionCenterServiceImpl(JdbcTemplate jdbc, MdmItemVersionDao itemVersionDao,
                                       MdmEventBus eventBus, AuditLogWriter auditLog) {
        this.jdbc = jdbc;
        this.itemVersionDao = itemVersionDao;
        this.eventBus = eventBus;
        this.auditLog = auditLog;
    }

    private String[] mapping(String bizType) {
        String[] t = TABLES.get(bizType == null ? "" : bizType.toUpperCase());
        if (t == null) {
            throw new ServiceException(422, "未知主数据类型：" + bizType);
        }
        return t;
    }

    @Override
    public List<Map<String, Object>> entities(String bizType, String keyword, int page, int size) {
        String[] t = mapping(bizType);
        StringBuilder sql = new StringBuilder("SELECT ID AS id, ").append(t[2]).append(" AS code, ")
                .append(t[3]).append(" AS name FROM ").append(t[1])
                .append(" WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(t[2]).append(" LIKE ? OR ").append(t[3]).append(" LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY ").append(t[2]).append(" LIMIT ? OFFSET ?");
        int lim = Math.min(Math.max(size, 1), 100);
        args.add(lim);
        args.add(Math.max(page - 1, 0) * lim);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public List<Map<String, Object>> versions(String bizType, String entityId) {
        String[] t = mapping(bizType);
        return jdbc.queryForList("SELECT ID AS id, VERSION_NO AS versionNo, OP_TYPE AS opType, "
                        + "CHANGE_REASON AS changeReason, CHANGE_TYPE AS changeType, "
                        + "DIFF_SUMMARY AS diffSummary, CREATE_BY AS createBy, CREATE_DATE AS createDate, "
                        + "LENGTH(SNAPSHOT_JSON) AS snapshotSize "
                        + "FROM " + t[0] + " WHERE ENTITY_ID=? ORDER BY VERSION_NO DESC",
                entityId);
    }

    @Override
    public Map<String, Object> snapshot(String bizType, String entityId, int versionNo) {
        String[] t = mapping(bizType);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT VERSION_NO AS versionNo, SNAPSHOT_JSON AS snapshotJson, OP_TYPE AS opType, "
                        + "CHANGE_REASON AS changeReason, CREATE_BY AS createBy, CREATE_DATE AS createDate "
                        + "FROM " + t[0] + " WHERE ENTITY_ID=? AND VERSION_NO=?",
                entityId, versionNo);
        if (rows.isEmpty()) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return rows.get(0);
    }

    @Override
    public Map<String, Object> diff(String bizType, String entityId, int from, int to) {
        Map<String, Object> a = snapshot(bizType, entityId, from);
        Map<String, Object> b = snapshot(bizType, entityId, to);
        JsonNode fa = readTree(str(a.get("snapshotJson")));
        JsonNode fb = readTree(str(b.get("snapshotJson")));
        Set<String> keys = new LinkedHashSet<>();
        fa.fieldNames().forEachRemaining(keys::add);
        fb.fieldNames().forEachRemaining(keys::add);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String k : keys) {
            String va = text(fa, k);
            String vb = text(fb, k);
            String state;
            if (va.isEmpty() && !vb.isEmpty()) {
                state = "ADDED";
            } else if (!va.isEmpty() && vb.isEmpty()) {
                state = "REMOVED";
            } else if (!va.equals(vb)) {
                state = "CHANGED";
            } else {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", k);
            row.put("oldVal", va);
            row.put("newVal", vb);
            row.put("state", state);
            fields.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("fields", fields);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(String bizType, String entityId, int sourceVersionNo, String reason) {
        String type = bizType == null ? "" : bizType.toUpperCase();
        String[] t = mapping(type);
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "回滚原因必填（至少 2 字）");
        }
        Map<String, Object> src = snapshot(type, entityId, sourceVersionNo);
        JsonNode snap = readTree(str(src.get("snapshotJson")));

        // 当前主表行存在性 + 主表列集合（只回写快照中存在且主表存在的业务列）
        List<Map<String, Object>> main = jdbc.queryForList(
                "SELECT * FROM " + t[1] + " WHERE ID=? AND DEL_FLAG='0'", entityId);
        if (main.isEmpty()) {
            throw new ServiceException(404, "主数据不存在或已归档：" + entityId);
        }
        Map<String, Object> setArgs = new LinkedHashMap<>();
        StringBuilder sets = new StringBuilder();
        snap.fieldNames().forEachRemaining(field -> {
            String col = camelToCol(field);
            if (SKIP_COLS.contains(col) || !main.get(0).containsKey(col)) {
                return;
            }
            JsonNode v = snap.get(field);
            String val = (v == null || v.isNull()) ? null : v.asText();
            if (sets.length() > 0) {
                sets.append(", ");
            }
            sets.append(col).append("=?");
            setArgs.put(col, val);
        });
        if (sets.length() == 0) {
            throw new ServiceException(422, "源版本快照无可回写字段");
        }
        List<Object> args = new ArrayList<>(setArgs.values());
        args.add(entityId);
        int updated = jdbc.update("UPDATE " + t[1] + " SET " + sets + " WHERE ID=?", args.toArray());
        if (updated == 0) {
            throw new ServiceException(409, "回滚写回冲突（并发修改），请刷新后重试（C-4.1-05）");
        }

        // 新版本行 V(N+1)：内容=源快照，OP_TYPE=ROLLBACK
        int next = currentMaxVersion(t[0], entityId) + 1;
        jdbc.update("INSERT INTO " + t[0] + " (ID, ENTITY_ID, VERSION_NO, SNAPSHOT_JSON, DIFF_SUMMARY, "
                        + "OP_TYPE, CHANGE_REASON, CHANGE_TYPE, CREATE_BY, CREATE_DATE) "
                        + "VALUES (UUID(), ?, ?, ?, ?, 'ROLLBACK', ?, ?, ?, NOW())",
                entityId, next, str(src.get("snapshotJson")),
                "回滚自 V" + sourceVersionNo, reason.trim(), str(src.get("changeType")), operator());

        // 审计留痕（操作人/源版本/原因）
        auditLog.write(operator(), "MDM_ROLLBACK:" + type + ":" + entityId,
                "V" + sourceVersionNo, "V" + next, reason.trim());
        // 事件同步下游（缓存失效+监听器+外部投递）
        eventBus.publish("MDM." + type + ".UPDATED", type, entityId, next, List.of("__ROLLBACK__"), true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newVersionNo", next);
        out.put("sourceVersionNo", sourceVersionNo);
        return out;
    }

    // ---------------- 工具 ----------------

    private int currentMaxVersion(String versionTable, String entityId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(VERSION_NO),0) FROM " + versionTable
                + " WHERE ENTITY_ID=?", Integer.class, entityId);
        return max == null ? 0 : max;
    }

    private JsonNode readTree(String json) {
        try {
            return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new ServiceException(500, "快照 JSON 解析失败：" + e.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** camelCase -> UPPER_SNAKE 列名 */
    private String camelToCol(String field) {
        StringBuilder sb = new StringBuilder();
        for (char c : field.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}

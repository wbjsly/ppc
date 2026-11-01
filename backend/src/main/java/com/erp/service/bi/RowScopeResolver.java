package com.erp.service.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.dao.bi.BiPermRuleVerDao;
import com.erp.entity.bi.BiPermRule;
import com.erp.entity.bi.BiPermRuleVer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 行级数据范围解析（add-perm-control-console，spec bi-row-permission）：
 * - 多角色并集（BR-4.10-16）：用户名直配 ∪ 其全部角色规则
 * - 冲突取严：DENY > PASS；显式清单 > 子树 > 通配
 * - 三维度：法人主体 / 组织部门树（SELF_SUBTREE 递归）/ 数据密级（登记位）
 * - 异常回退（BR-4.10-17）：展开失败 → 上一版本快照重算 + 告警治理 + 安全日志；
 *   无上一版本 → 默认拒绝（空集）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RowScopeResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BiPermRuleDao permRuleDao;
    private final BiPermRuleVerDao verDao;
    private final JdbcTemplate jdbc;

    /**
     * 解析用户最终可见法人主体集合。
     * 返回 null = 全量（通配 PASS 且无 DENY）；空集合 = 默认拒绝。
     * 每次调用独立实例状态由 fallbackNotes 承载（见 resolveDetailed）。
     */
    public List<String> resolveEntities(String userName) {
        return resolveDetailed(userName).allowed;
    }

    /** 详细解析：范围 + 每个主体的生效规则来源（影响预览 BR-4.10-16 审计追溯） */
    public Result resolveDetailed(String userName) {
        Result out = new Result();
        if (userName == null || userName.trim().isEmpty()) {
            out.allowed = new ArrayList<>();
            return out;
        }
        List<BiPermRule> rules = collectRules(userName);
        Set<String> passEntities = new HashSet<>();
        Set<String> denyEntities = new HashSet<>();
        boolean passWildcard = false;
        boolean denyWildcard = false;
        for (BiPermRule r : rules) {
            Scope scope = expand(r, out.notes);
            if (scope == null) {
                continue; // 展开失败且已回退/告警，或规则不适用
            }
            String source = r.getUserName() == null
                    ? "role:" + r.getRoleCode() + "#" + r.getId()
                    : "user:" + r.getUserName() + "#" + r.getId();
            if ("DENY".equals(r.getAction())) {
                if (scope.wildcard) {
                    denyWildcard = true;
                } else {
                    denyEntities.addAll(scope.entityIds);
                    for (String e : scope.entityIds) {
                        out.sources.put(e, "DENY " + source);
                    }
                }
            } else {
                if (scope.wildcard) {
                    passWildcard = true;
                } else {
                    passEntities.addAll(scope.entityIds);
                    for (String e : scope.entityIds) {
                        out.sources.putIfAbsent(e, "PASS " + source);
                    }
                }
            }
        }
        if (denyWildcard) {
            out.allowed = new ArrayList<>(); // 通配 DENY 严于一切 PASS
            return out;
        }
        if (passWildcard) {
            if (denyEntities.isEmpty()) {
                out.allowed = null; // 全量，与既有消费语义兼容
                return out;
            }
            // 通配 PASS + 显式 DENY → 全量清单减去排除集
            List<String> all = allLegalEntities();
            all.removeAll(denyEntities);
            out.allowed = all;
            return out;
        }
        passEntities.removeAll(denyEntities);
        out.allowed = new ArrayList<>(passEntities);
        return out;
    }

    // ---- 规则收集 ----

    private List<BiPermRule> collectRules(String userName) {
        List<BiPermRule> out = new ArrayList<>();
        List<BiPermRule> direct = permRuleDao.selectList(new LambdaQueryWrapper<BiPermRule>()
                .likeRight(BiPermRule::getRuleType, "ROW")
                .eq(BiPermRule::getUserName, userName)
                .eq(BiPermRule::getStatus, "ENABLED"));
        out.addAll(direct);
        List<String> roles = rolesOf(userName);
        if (!roles.isEmpty()) {
            List<BiPermRule> byRole = permRuleDao.selectRowByRoles(roles);
            // selectRowByRoles 的 ROLE_CODE 匹配；排除按用户收集已覆盖的行
            for (BiPermRule r : byRole) {
                if (!userName.equals(r.getUserName())) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private List<String> rolesOf(String userName) {
        return jdbc.queryForList(
                "SELECT r.ROLE_CODE FROM erp_admin_user u "
                        + "JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE u.USERNAME = ? AND u.DEL_FLAG = '0'",
                String.class, userName);
    }

    // ---- 范围展开 ----

    private static final class Scope {
        boolean wildcard;
        List<String> entityIds = new ArrayList<>();
    }

    private Scope expand(BiPermRule r, List<String> notes) {
        try {
            return expandCurrent(r);
        } catch (Exception e) {
            log.warn("row rule expand failed rule={} : {}", r.getId(), e.getMessage());
            return fallback(r, e.getMessage(), notes);
        }
    }

    private Scope expandCurrent(BiPermRule r) {
        String expr = r.getScopeExpr();
        if (expr == null || expr.trim().isEmpty()) {
            // 存量直配语义：ENTITY_ID（* = 通配）
            Scope s = new Scope();
            if ("*".equals(r.getEntityId())) {
                s.wildcard = true;
            } else if (r.getEntityId() != null) {
                s.entityIds.add(r.getEntityId());
            }
            return s;
        }
        switch (expr) {
            case "SELF_SUBTREE": {
                if (r.getOrgId() == null || r.getOrgId().trim().isEmpty()) {
                    throw new IllegalArgumentException("SELF_SUBTREE 缺少组织锚 ORG_ID");
                }
                Scope s = new Scope();
                s.entityIds.addAll(entitiesOfSubtree(r.getOrgId()));
                return s;
            }
            case "LIST": {
                if (r.getScopeList() == null || r.getScopeList().trim().isEmpty()) {
                    throw new IllegalArgumentException("LIST 模式清单为空");
                }
                List<String> orgs;
                try {
                    orgs = JSON.readValue(r.getScopeList(),
                            new TypeReference<List<String>>() { });
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException("LIST 清单 JSON 解析失败: " + e.getMessage());
                }
                if (orgs.isEmpty()) {
                    throw new IllegalArgumentException("LIST 模式清单为空");
                }
                Scope s = new Scope();
                for (String ou : orgs) {
                    s.entityIds.addAll(entitiesOfSubtree(ou));
                }
                return s;
            }
            case "SELF": {
                // 本人所属组织 → 法人主体（当前组织与用户绑定关系未建模时退化为
                // 用户直配 ENTITY_ID；有绑定则取其组织的法人主体）
                Scope s = new Scope();
                if (r.getEntityId() != null && !"*".equals(r.getEntityId())) {
                    s.entityIds.add(r.getEntityId());
                }
                return s;
            }
            default:
                throw new IllegalArgumentException("非法 SCOPE_EXPR：" + expr);
        }
    }

    /** 组织子树（含自身）映射到法人主体集合；节点不存在 → 抛异常触发回退（BR-4.10-17） */
    private List<String> entitiesOfSubtree(String rootOrgId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mdm_org_unit WHERE ID = ? AND DEL_FLAG = '0'",
                Integer.class, rootOrgId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("组织节点不存在或已删除：" + rootOrgId);
        }
        // 递归子树（组织表数据量小，WITH RECURSIVE 一次取全）
        return jdbc.queryForList(
                "WITH RECURSIVE sub AS ("
                        + "SELECT ID, PARENT_ID, LEGAL_ENTITY_ID FROM erp_mdm_org_unit WHERE ID = ? "
                        + "UNION ALL "
                        + "SELECT o.ID, o.PARENT_ID, o.LEGAL_ENTITY_ID FROM erp_mdm_org_unit o "
                        + "JOIN sub ON o.PARENT_ID = sub.ID) "
                        + "SELECT DISTINCT LEGAL_ENTITY_ID FROM sub WHERE LEGAL_ENTITY_ID IS NOT NULL",
                String.class, rootOrgId);
    }

    /** 全部法人主体（通配 PASS + 显式 DENY 折算用） */
    private List<String> allLegalEntities() {
        return jdbc.queryForList(
                "SELECT DISTINCT LEGAL_ENTITY_ID FROM erp_mdm_org_unit "
                        + "WHERE DEL_FLAG = '0' AND LEGAL_ENTITY_ID IS NOT NULL",
                String.class);
    }

    // ---- 异常回退（BR-4.10-17）----

    private Scope fallback(BiPermRule r, String reason, List<String> notes) {
        // 取上一版本快照重算
        List<BiPermRuleVer> vers = verDao.selectList(new LambdaQueryWrapper<BiPermRuleVer>()
                .eq(BiPermRuleVer::getRuleId, r.getId())
                .orderByDesc(BiPermRuleVer::getRuleVer)
                .last("LIMIT 2"));
        BiPermRuleVer prev = null;
        for (BiPermRuleVer v : vers) {
            // 快照是"生效时点"状态；当前规则已是最新快照语义，取序号更小者为上一版本
            if (v.getRuleVer() != null && v.getRuleVer() < maxVer(v.getRuleId())) {
                prev = v;
                break;
            }
        }
        String note;
        if (prev != null) {
            try {
                BiPermRule snap = fromSnapshot(prev);
                Scope s = expandCurrent(snap);
                note = "规则 " + r.getId() + " 展开失败（" + reason + "），已回退上一版本 V"
                        + prev.getRuleVer();
                notes.add(note);
                alertAndLog(r.getId(), note);
                return s;
            } catch (Exception e2) {
                note = "规则 " + r.getId() + " 上一版本快照回退仍失败：" + e2.getMessage();
            }
        } else {
            note = "规则 " + r.getId() + " 展开失败且无上一版本可回退：" + reason;
        }
        // 无回退 → 默认拒绝（返回空 Scope 视为该规则贡献空集）
        notes.add(note);
        alertAndLog(r.getId(), note);
        Scope s = new Scope();
        return s;
    }

    private int maxVer(String ruleId) {
        Integer m = jdbc.queryForObject(
                "SELECT COALESCE(MAX(RULE_VER), 0) FROM erp_bi_perm_rule_ver WHERE RULE_ID = ?",
                Integer.class, ruleId);
        return m == null ? 0 : m;
    }

    private BiPermRule fromSnapshot(BiPermRuleVer v) {
        try {
            Map<String, Object> m = JSON.readValue(v.getSnapshotJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            BiPermRule r = new BiPermRule();
            r.setId(v.getRuleId());
            r.setRuleType(str(m.get("ruleType")));
            r.setTargetKey(str(m.get("targetKey")));
            r.setUserName(str(m.get("userName")));
            r.setEntityId(str(m.get("entityId")));
            r.setRoleCode(str(m.get("roleCode")));
            r.setAction(str(m.get("action")));
            r.setMaskStyle(str(m.get("maskStyle")));
            r.setStatus(str(m.get("status")));
            r.setScopeExpr(str(m.get("scopeExpr")));
            r.setOrgId(str(m.get("orgId")));
            r.setSecLevel(str(m.get("secLevel")));
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("快照解析失败: " + e.getMessage());
        }
    }

    private void alertAndLog(String ruleId, String note) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_DATA_GOV', 'BiRowPermFallback', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), ruleId, "行级权限规则异常回退告警", note);
        } catch (Exception e) {
            log.warn("row perm fallback alert failed: {}", e.getMessage());
        }
        try {
            // 安全日志（复用 hr_change_log 结构，FIELD_NAME 标识类别）
            jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, "
                            + "NEW_VALUE, REASON, CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                    uuid(), ruleId, "BI_ROW_RULE_FALLBACK", ruleId, "FALLBACK", note,
                    "system", LocalDateTime.now());
        } catch (Exception e) {
            log.warn("row perm fallback security log failed: {}", e.getMessage());
        }
    }

    /** 影响预览结果 */
    public static final class Result {
        /** null = 全量 */
        public List<String> allowed;
        public final Map<String, String> sources = new HashMap<>();
        public final List<String> notes = new ArrayList<>();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

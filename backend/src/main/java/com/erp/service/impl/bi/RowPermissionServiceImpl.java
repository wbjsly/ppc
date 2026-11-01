package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.dao.bi.BiPermRuleVerDao;
import com.erp.entity.bi.BiPermRule;
import com.erp.entity.bi.BiPermRuleVer;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.RowPermissionService;
import com.erp.service.bi.RowScopeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 行级权限管理台实现（add-perm-control-console，spec bi-row-permission）。
 * 状态机 DRAFT → PENDING_APPROVAL → ENABLED（ROLE_DATA_GOV 单签）；
 * 每次生效变更插版本快照（BR-4.10-17 回退锚）；受控 SCOPE_EXPR 不引入自由表达式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RowPermissionServiceImpl implements RowPermissionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 受控范围表达式（design D2：不引入自由表达式引擎） */
    public static final Set<String> SCOPE_EXPRS = Set.of("SELF", "SELF_SUBTREE", "LIST");

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private final BiPermRuleDao permRuleDao;
    private final BiPermRuleVerDao verDao;
    private final ApprovalEngine approvalEngine;
    private final RowScopeResolver rowScopeResolver;
    private final JdbcTemplate jdbc;

    @Override
    public List<Map<String, Object>> list(String userName, String roleCode, String status) {
        LambdaQueryWrapper<BiPermRule> qw = new LambdaQueryWrapper<BiPermRule>()
                .likeRight(BiPermRule::getRuleType, "ROW")
                .orderByDesc(BiPermRule::getCreateDate);
        if (StringUtils.hasText(userName)) {
            qw.eq(BiPermRule::getUserName, userName);
        }
        if (StringUtils.hasText(roleCode)) {
            qw.eq(BiPermRule::getRoleCode, roleCode);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(BiPermRule::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiPermRule r : permRuleDao.selectList(qw)) {
            out.add(view(r));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String userName = str(payload.get("userName"));
        String roleCode = str(payload.get("roleCode"));
        if (!StringUtils.hasText(userName) && !StringUtils.hasText(roleCode)) {
            throw new ServiceException(422, "授权对象必填其一（用户或角色）");
        }
        if (StringUtils.hasText(userName) && StringUtils.hasText(roleCode)) {
            throw new ServiceException(422, "授权对象仅可二选一（用户或角色）");
        }
        BiPermRule r = new BiPermRule();
        r.setId(uuid());
        r.setRuleType("ROW_LE");
        r.setTargetKey("*");
        r.setUserName(userName);
        r.setRoleCode(roleCode);
        r.setAction(actionOf(payload.get("action")));
        applyScope(r, payload);
        r.setStatus(ST_DRAFT);
        r.setRemark(str(payload.get("remark")));
        r.setCreateBy(operator());
        permRuleDao.insert(r);
        log.info("行级规则创建 {} user={} role={} expr={} by {}",
                r.getId(), userName, roleCode, r.getScopeExpr(), operator());
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        BiPermRule r = require(id);
        if (!ST_DRAFT.equals(r.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿状态可修改（当前：" + r.getStatus() + "），已生效规则请新建后走审批");
        }
        if (payload.containsKey("action")) {
            r.setAction(actionOf(payload.get("action")));
        }
        if (payload.containsKey("scopeExpr") || payload.containsKey("orgId")
                || payload.containsKey("secLevel") || payload.containsKey("scopeList")
                || payload.containsKey("entityId")) {
            applyScope(r, payload);
        }
        if (payload.get("remark") != null) {
            r.setRemark(str(payload.get("remark")));
        }
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        return Map.of("id", id, "status", r.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        BiPermRule r = require(id);
        if (ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "已在审批中，不可重复提交");
        }
        if (!ST_DRAFT.equals(r.getStatus()) && !ST_DISABLED.equals(r.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿/停用状态可提交审批（当前：" + r.getStatus() + "）");
        }
        r.setStatus(ST_PENDING);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-行级权限")));
        var inst = approvalEngine.submit("BiRowPerm", id,
                "行级权限审批：" + subject(r) + "（" + scopeLabel(r) + "）",
                "ROLE_DATA_GOV", chain);
        log.info("行级规则提交审批 {} {} by {}", id, subject(r), operator());
        return Map.of("id", id, "status", r.getStatus(), "approvalInstanceId", inst.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> disable(String id) {
        BiPermRule r = require(id);
        if (ST_DISABLED.equals(r.getStatus())) {
            throw new ServiceException(422, "规则已停用");
        }
        if (ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "审批中的规则不可停用，请先驳回");
        }
        r.setStatus(ST_DISABLED);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        snapshot(r);
        return Map.of("id", id, "status", r.getStatus());
    }

    @Override
    public List<Map<String, Object>> versions(String ruleId) {
        List<BiPermRuleVer> vers = verDao.selectList(new LambdaQueryWrapper<BiPermRuleVer>()
                .eq(BiPermRuleVer::getRuleId, ruleId)
                .orderByDesc(BiPermRuleVer::getRuleVer));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiPermRuleVer v : vers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("ruleVer", v.getRuleVer());
            m.put("snapshotJson", v.getSnapshotJson());
            m.put("effDate", v.getEffDate());
            m.put("createBy", v.getCreateBy());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> impactPreview(String userName) {
        RowScopeResolver.Result r = rowScopeResolver.resolveDetailed(userName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userName", userName);
        out.put("allEntities", r.allowed == null);
        out.put("allowedEntities", r.allowed == null ? "ALL" : r.allowed);
        out.put("sources", r.sources);
        out.put("fallbackNotes", r.notes);
        return out;
    }

    // ---- 回调入口 ----

    @Override
    @Transactional
    public void activateOnApproved(String ruleId) {
        BiPermRule r = require(ruleId);
        r.setStatus(ST_ENABLED);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        snapshot(r);
        log.info("行级规则审批通过启用 {} {} by {}", ruleId, subject(r), operator());
    }

    @Override
    @Transactional
    public void rejectOnCallback(String ruleId) {
        BiPermRule r = require(ruleId);
        r.setStatus(ST_DRAFT);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_BI_DESIGNER', 'BiRowPermApproval', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), ruleId, "行级权限规则被驳回：" + subject(r),
                    "规则已退回草稿，可修改后重新提交（" + scopeLabel(r) + "）");
        } catch (Exception e) {
            log.warn("row perm reject notify failed: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    /** 生效变更插版本快照（BR-4.10-17 回退锚，不可变） */
    private void snapshot(BiPermRule r) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(RULE_VER), 0) FROM erp_bi_perm_rule_ver WHERE RULE_ID = ?",
                Integer.class, r.getId());
        BiPermRuleVer v = new BiPermRuleVer();
        v.setId(uuid());
        v.setRuleId(r.getId());
        v.setRuleVer((max == null ? 0 : max) + 1);
        Map<String, String> snap = new LinkedHashMap<>();
        snap.put("ruleType", nz(r.getRuleType()));
        snap.put("targetKey", nz(r.getTargetKey()));
        snap.put("userName", nz(r.getUserName()));
        snap.put("entityId", nz(r.getEntityId()));
        snap.put("roleCode", nz(r.getRoleCode()));
        snap.put("action", nz(r.getAction()));
        snap.put("maskStyle", nz(r.getMaskStyle()));
        snap.put("status", nz(r.getStatus()));
        snap.put("scopeExpr", nz(r.getScopeExpr()));
        snap.put("orgId", nz(r.getOrgId()));
        snap.put("secLevel", nz(r.getSecLevel()));
        snap.put("scopeList", nz(r.getScopeList()));
        try {
            v.setSnapshotJson(JSON.writeValueAsString(snap));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("规则快照序列化失败: " + e.getMessage());
        }
        v.setEffDate(LocalDateTime.now());
        v.setCreateBy(operator());
        verDao.insert(v);
    }

    /** SCOPE_EXPR 受控枚举与维度校验（spec：非法枚举 422） */
    private void applyScope(BiPermRule r, Map<String, Object> payload) {
        String expr = str(payload.get("scopeExpr"));
        if (!StringUtils.hasText(expr)) {
            throw new ServiceException(422,
                    "范围表达式必填（受控枚举 " + SCOPE_EXPRS + "），不接受自由表达式");
        }
        expr = expr.toUpperCase();
        if (!SCOPE_EXPRS.contains(expr)) {
            throw new ServiceException(422, "非法 SCOPE_EXPR：" + expr + "，仅 " + SCOPE_EXPRS);
        }
        r.setScopeExpr(expr);
        r.setSecLevel(str(payload.get("secLevel")));
        switch (expr) {
            case "SELF_SUBTREE": {
                String orgId = str(payload.get("orgId"));
                if (!StringUtils.hasText(orgId)) {
                    throw new ServiceException(422, "SELF_SUBTREE 模式必须指定组织锚 ORG_ID");
                }
                r.setOrgId(orgId);
                r.setScopeList(null);
                break;
            }
            case "LIST": {
                String listJson = str(payload.get("scopeList"));
                if (!StringUtils.hasText(listJson)) {
                    throw new ServiceException(422, "LIST 模式清单不能为空");
                }
                try {
                    List<String> orgs = JSON.readValue(listJson,
                            new TypeReference<List<String>>() { });
                    if (orgs.isEmpty()) {
                        throw new ServiceException(422, "LIST 模式清单不能为空");
                    }
                } catch (ServiceException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ServiceException(422, "LIST 清单须为组织 ID 的 JSON 数组");
                }
                r.setScopeList(listJson);
                r.setOrgId(null);
                break;
            }
            default: {
                // SELF：可选直配法人主体
                r.setEntityId(str(payload.get("entityId")));
                r.setOrgId(null);
                r.setScopeList(null);
            }
        }
        // 三维度组合：法人主体可与组织/密级叠加（直配 entityId 独立于 expr 展开）
        if (payload.get("entityId") != null && !"SELF".equals(expr)) {
            r.setEntityId(str(payload.get("entityId")));
        }
    }

    private String actionOf(Object o) {
        String a = str(o);
        if (!StringUtils.hasText(a)) {
            return "PASS";
        }
        a = a.toUpperCase();
        if (!"PASS".equals(a) && !"DENY".equals(a)) {
            throw new ServiceException(422, "动作仅 PASS/DENY");
        }
        return a;
    }

    private Map<String, Object> view(BiPermRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("ruleType", r.getRuleType());
        m.put("userName", r.getUserName());
        m.put("roleCode", r.getRoleCode());
        m.put("entityId", r.getEntityId());
        m.put("scopeExpr", r.getScopeExpr());
        m.put("orgId", r.getOrgId());
        m.put("secLevel", r.getSecLevel());
        m.put("scopeList", r.getScopeList());
        m.put("action", r.getAction());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        m.put("createBy", r.getCreateBy());
        m.put("createDate", r.getCreateDate());
        return m;
    }

    private String subject(BiPermRule r) {
        return StringUtils.hasText(r.getUserName()) ? "用户:" + r.getUserName()
                : "角色:" + r.getRoleCode();
    }

    private String scopeLabel(BiPermRule r) {
        if (StringUtils.hasText(r.getScopeExpr())) {
            return r.getScopeExpr() + (StringUtils.hasText(r.getOrgId())
                    ? "(" + r.getOrgId() + ")" : "");
        }
        return "ENTITY:" + r.getEntityId();
    }

    private BiPermRule require(String id) {
        BiPermRule r = permRuleDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "行级规则不存在：" + id);
        }
        return r;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String operator() {
        try {
            String u = com.erp.util.SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}

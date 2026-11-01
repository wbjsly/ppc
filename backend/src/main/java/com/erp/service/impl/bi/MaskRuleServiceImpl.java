package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiMaskRuleLogDao;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.entity.bi.BiMaskRuleLog;
import com.erp.entity.bi.BiPermRule;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.MaskRuleService;
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
 * 脱敏规则生命周期实现（add-data-extract-console，spec bi-mask-rule-management）。
 * 状态机 DRAFT → PENDING_APPROVAL → ENABLED（ROLE_DATA_GOV 单签审批激活）；
 * 仅 ENABLED 参与 QueryGovernance 掩码判定；变更逐次留痕。
 */
@Slf4j
// 显式 bean 名：与 16.7.3 系统域 com.erp.service.impl.MaskRuleServiceImpl 类简名冲突（Spring 默认名撞车）
@Service("biMaskRuleServiceImpl")
@RequiredArgsConstructor
public class MaskRuleServiceImpl implements MaskRuleService {

    /** 样式集与严格度（design D3：SALARY > ID_CARD=PHONE=BANK > GENERIC） */
    public static final Set<String> STYLES = Set.of(
            "PHONE", "ID_CARD", "SALARY", "BANK", "GENERIC");

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private final BiPermRuleDao permRuleDao;
    private final BiMaskRuleLogDao logDao;
    private final ApprovalEngine approvalEngine;
    private final JdbcTemplate jdbc;

    @Override
    public List<Map<String, Object>> list(String ruleType) {
        LambdaQueryWrapper<BiPermRule> qw = new LambdaQueryWrapper<BiPermRule>()
                .in(BiPermRule::getRuleType, BiPermRule.TYPE_COL_MASK, BiPermRule.TYPE_COL_SENSITIVE)
                .orderByAsc(BiPermRule::getRuleType);
        if (StringUtils.hasText(ruleType)) {
            qw.eq(BiPermRule::getRuleType, ruleType);
        }
        List<BiPermRule> rules = permRuleDao.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiPermRule r : rules) {
            out.add(view(r));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String targetKey = str(payload.get("targetKey"));
        if (!StringUtils.hasText(targetKey)) {
            throw new ServiceException(422, "目标字段必填（可用通配 *）");
        }
        String ruleType = StringUtils.hasText(str(payload.get("ruleType")))
                ? str(payload.get("ruleType")) : BiPermRule.TYPE_COL_MASK;
        if (!BiPermRule.TYPE_COL_MASK.equals(ruleType)
                && !BiPermRule.TYPE_COL_SENSITIVE.equals(ruleType)) {
            throw new ServiceException(422, "规则类型仅 COL_MASK/COL_SENSITIVE");
        }
        String style = StringUtils.hasText(str(payload.get("maskStyle")))
                ? str(payload.get("maskStyle")).toUpperCase() : "GENERIC";
        if (!STYLES.contains(style)) {
            throw new ServiceException(422, "掩码样式仅 " + STYLES);
        }
        BiPermRule r = new BiPermRule();
        r.setId(uuid());
        r.setRuleType(ruleType);
        r.setTargetKey(targetKey.trim());
        r.setRoleCode(str(payload.get("roleCode")));
        r.setAction(BiPermRule.TYPE_COL_SENSITIVE.equals(ruleType)
                ? "PASS" : "MASK");
        r.setMaskStyle(style);
        r.setStatus(ST_DRAFT);
        r.setRemark(str(payload.get("remark")));
        r.setCreateBy(operator());
        permRuleDao.insert(r);
        addLog(r.getId(), BiMaskRuleLog.ACT_CREATE, null, style + "/" + r.getTargetKey());
        log.info("脱敏规则创建 {} {} style={} by {}", r.getId(), r.getTargetKey(), style, operator());
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
        if (payload.get("maskStyle") != null) {
            String style = str(payload.get("maskStyle")).toUpperCase();
            if (!STYLES.contains(style)) {
                throw new ServiceException(422, "掩码样式仅 " + STYLES);
            }
            addLog(id, BiMaskRuleLog.ACT_UPDATE_STYLE, r.getMaskStyle(), style);
            r.setMaskStyle(style);
        }
        if (payload.containsKey("roleCode")) {
            String role = str(payload.get("roleCode"));
            addLog(id, BiMaskRuleLog.ACT_UPDATE_ROLE, r.getRoleCode(), role);
            r.setRoleCode(role);
        }
        if (payload.get("targetKey") != null) {
            r.setTargetKey(str(payload.get("targetKey")).trim());
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
    public Map<String, Object> disable(String id) {
        BiPermRule r = require(id);
        if (ST_DISABLED.equals(r.getStatus())) {
            throw new ServiceException(422, "规则已停用");
        }
        if (ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "审批中的规则不可停用，请先驳回");
        }
        String old = r.getStatus();
        r.setStatus(ST_DISABLED);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        addLog(id, BiMaskRuleLog.ACT_DISABLE, old, ST_DISABLED);
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
        // 数据治理专员单签（FR-4.10-2-3 要点③）
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-脱敏合规")));
        var inst = approvalEngine.submit("MaskRule", id,
                "脱敏规则审批：" + r.getTargetKey() + "（" + r.getMaskStyle() + "）",
                "ROLE_DATA_GOV", chain);
        log.info("脱敏规则提交审批 {} {} by {}", id, r.getTargetKey(), operator());
        return Map.of("id", id, "status", r.getStatus(), "approvalInstanceId", inst.getId());
    }

    @Override
    public List<Map<String, Object>> logs(String ruleId) {
        return jdbc.queryForList(
                "SELECT ID AS id, RULE_ID AS ruleId, ACTION AS action, OLD_VAL AS oldVal, "
                        + "NEW_VAL AS newVal, OPERATOR AS operator, OP_AT AS opAt "
                        + "FROM erp_bi_mask_rule_log WHERE RULE_ID = ? ORDER BY OP_AT DESC",
                ruleId);
    }

    // ---- 回调入口 ----

    /** 审批通过：PENDING_APPROVAL → ENABLED + 留痕（回调内） */
    @Transactional
    public void activateOnApproved(String ruleId) {
        BiPermRule r = require(ruleId);
        r.setStatus(ST_ENABLED);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        addLog(ruleId, BiMaskRuleLog.ACT_ENABLE, ST_PENDING, ST_ENABLED);
        log.info("脱敏规则审批通过启用 {} {} by {}", ruleId, r.getTargetKey(), operator());
    }

    /** 审批驳回：→ DRAFT + 留痕 + 广播通知（回调内） */
    @Transactional
    public void rejectOnCallback(String ruleId) {
        BiPermRule r = require(ruleId);
        r.setStatus(ST_DRAFT);
        r.setUpdateBy(operator());
        permRuleDao.updateById(r);
        addLog(ruleId, BiMaskRuleLog.ACT_REJECT, ST_PENDING, ST_DRAFT);
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_BI_DESIGNER', 'MaskRuleApproval', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), ruleId, "脱敏规则被驳回：" + r.getTargetKey(),
                    "规则已退回草稿，可修改后重新提交（" + r.getMaskStyle() + "）");
        } catch (Exception e) {
            log.warn("mask rule reject notify failed: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    private void addLog(String ruleId, String action, String oldVal, String newVal) {
        BiMaskRuleLog l = new BiMaskRuleLog();
        l.setId(uuid());
        l.setRuleId(ruleId);
        l.setAction(action);
        l.setOldVal(oldVal);
        l.setNewVal(newVal);
        l.setOperator(operator());
        l.setOpAt(LocalDateTime.now());
        l.setCreateBy(operator());
        logDao.insert(l);
    }

    private Map<String, Object> view(BiPermRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("ruleType", r.getRuleType());
        m.put("targetKey", r.getTargetKey());
        m.put("roleCode", r.getRoleCode());
        m.put("action", r.getAction());
        m.put("maskStyle", r.getMaskStyle() == null ? "GENERIC" : r.getMaskStyle());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        m.put("updateBy", r.getUpdateBy());
        m.put("updateDate", r.getUpdateDate());
        return m;
    }

    private BiPermRule require(String id) {
        BiPermRule r = permRuleDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "脱敏规则不存在：" + id);
        }
        return r;
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

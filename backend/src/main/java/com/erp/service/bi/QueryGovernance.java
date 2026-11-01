package com.erp.service.bi;

import com.erp.common.ServiceException;
import com.erp.dao.bi.BiExportTaskDao;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.entity.bi.BiExportTask;
import com.erp.entity.bi.BiPermRule;
import com.erp.security.IntfGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 查询治理（spec bi-query-governance，design D6）：
 * - 行级：用户绑定法人主体集合（查询层显式注入依据；无绑定=空集默认拒绝）
 * - 列级：命中 COL_MASK 掩码、COL_SENSITIVE 未命中默认掩码（C-4.10-02）
 * - 审计：每次查询逐次落 TASK_TYPE=QUERY 行（≥365 天）
 */
@Slf4j
@Component
public class QueryGovernance {

    private final BiPermRuleDao permRuleDao;
    private final BiExportTaskDao exportTaskDao;
    private final RowScopeResolver rowScopeResolver;
    private final TempGrantService tempGrantService;

    public QueryGovernance(BiPermRuleDao permRuleDao, BiExportTaskDao exportTaskDao,
                           RowScopeResolver rowScopeResolver, TempGrantService tempGrantService) {
        this.permRuleDao = permRuleDao;
        this.exportTaskDao = exportTaskDao;
        this.rowScopeResolver = rowScopeResolver;
        this.tempGrantService = tempGrantService;
    }

    /**
     * 行级过滤（add-perm-control-console 重构，spec bi-row-permission）：
     * 多角色并集 + 冲突取严（BR-4.10-16）+ 异常回退默认拒绝（BR-4.10-17）。
     * 返回允许访问的法人主体 ID 列表；通配 PASS 且无 DENY → null（不过滤=全量）；
     * 无任何有效授权 → 空集合（调用方必须据此查空，不得放开）。
     */
    public List<String> allowedEntities(String userName) {
        return rowScopeResolver.resolveEntities(userName);
    }

    /**
     * 列掩码（add-data-extract-console design D3 类型化增强）：
     * - 仅 ENABLED 规则参与（DRAFT/PENDING_APPROVAL 不生效，spec bi-mask-rule-management）
     * - 角色差异化：规则绑定 ROLE_CODE 时仅对持有该角色用户生效；未绑定对全体生效
     * - 多命中取最严样式（SALARY > ID_CARD=PHONE=BANK > GENERIC）；同级字段级优先于通配
     * - COL_SENSITIVE 登记但未命中规则 → GENERIC 默认掩码（C-4.10-02）
     */
    public Object maskIfHit(String column, Object value) {
        if (value == null) {
            return null;
        }
        List<BiPermRule> rules = permRuleDao.selectColRules();
        java.util.Set<String> myRoles = currentRoles();
        // 临时授权（add-perm-control-console spec bi-temp-grant）：有效授权放行明文，
        // 不扩行级范围（行级由 allowedEntities 独立约束）
        if (tempGrantService.hasActive(currentUser(), column)) {
            return value;
        }
        boolean sensitive = false;
        BiPermRule best = null;
        for (BiPermRule r : rules) {
            boolean hit = "*".equals(r.getTargetKey())
                    || column.equalsIgnoreCase(r.getTargetKey());
            if (!hit) {
                continue;
            }
            // 角色差异化：绑定角色的规则仅对持有者生效；未绑定全体生效
            if (StringUtils.hasText(r.getRoleCode()) && !myRoles.contains(r.getRoleCode())) {
                continue;
            }
            if (BiPermRule.TYPE_COL_SENSITIVE.equals(r.getRuleType())) {
                sensitive = true;
            }
            if (BiPermRule.TYPE_COL_MASK.equals(r.getRuleType())
                    && "MASK".equals(r.getAction())) {
                if (best == null || stricter(r, best, column)) {
                    best = r;
                }
            }
        }
        if (best != null) {
            return applyStyle(styleOf(best), String.valueOf(value));
        }
        if (sensitive) {
            // 未命中任何规则 → GENERIC 默认掩码（C-4.10-02）
            return applyStyle("GENERIC", String.valueOf(value));
        }
        return value;
    }

    /** 样式严格度比较：true = r 比 best 更严（或同级但字段级优先于通配） */
    private boolean stricter(BiPermRule r, BiPermRule best, String column) {
        int rr = rank(r), br = rank(best);
        if (rr != br) {
            return rr < br;
        }
        boolean rExact = !"*".equals(r.getTargetKey()) && column.equalsIgnoreCase(r.getTargetKey());
        boolean bExact = !"*".equals(best.getTargetKey()) && column.equalsIgnoreCase(best.getTargetKey());
        return rExact && !bExact;
    }

    /** 严格度：SALARY(0) > ID_CARD=PHONE=BANK(1) > GENERIC/未登记(2) */
    private int rank(BiPermRule r) {
        String s = styleOf(r);
        switch (s) {
            case "SALARY":
                return 0;
            case "ID_CARD":
            case "PHONE":
            case "BANK":
                return 1;
            default:
                return 2;
        }
    }

    private String styleOf(BiPermRule r) {
        return StringUtils.hasText(r.getMaskStyle()) ? r.getMaskStyle() : "GENERIC";
    }

    /** 类型化掩码函数（spec bi-mask-rule-management） */
    private Object applyStyle(String style, String s) {
        switch (style) {
            case "SALARY":
                return "***";
            case "BANK":
                // 保留后4位
                return s.length() <= 4 ? "***" : "****" + s.substring(s.length() - 4);
            case "PHONE":
            case "ID_CARD":
                // 前3后4（138****1234 / 110***…1234）
                if (s.length() <= 7) {
                    return "**";
                }
                return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
            default:
                // GENERIC：首尾保留中间 ***（兼容旧行为）
                if (s.length() <= 2) {
                    return "**";
                }
                return s.charAt(0) + "***" + s.charAt(s.length() - 1);
        }
    }

    /** 当前用户名（临时授权匹配用） */
    private String currentUser() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前用户角色集合（列级规则角色差异化用） */
    private java.util.Set<String> currentRoles() {
        java.util.Set<String> out = new java.util.HashSet<>();
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null) {
            auth.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .forEach(out::add);
        }
        return out;
    }

    /** 查询审计（逐次、≥365 天、不可改由 DB 保证无 UPDATE 入口） */
    public void auditQuery(String apiPath, Map<String, Object> params, List<String> maskCols, long rows) {
        try {
            BiExportTask a = new BiExportTask();
            a.setTaskType("QUERY");
            a.setApiPath(apiPath);
            a.setParamsJson(params == null ? "{}" : String.valueOf(params));
            a.setMaskCols(maskCols == null ? null : String.join(",", maskCols));
            a.setUserName(IntfGuard.currentUser());
            a.setRowCount(rows);
            a.setStatus("DONE");
            a.setReqId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            exportTaskDao.insert(a);
        } catch (Exception e) {
            log.warn("query audit failed: {}", e.getMessage());
        }
    }
}

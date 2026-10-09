package com.erp.service.bi;

import com.erp.common.ServiceException;
import com.erp.dao.bi.BiExportTaskDao;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.entity.bi.BiExportTask;
import com.erp.entity.bi.BiPermRule;
import com.erp.security.IntfGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public QueryGovernance(BiPermRuleDao permRuleDao, BiExportTaskDao exportTaskDao) {
        this.permRuleDao = permRuleDao;
        this.exportTaskDao = exportTaskDao;
    }

    /**
     * 行级过滤：返回允许访问的法人主体 ID 列表。
     * 通配规则（entityId='*'}）→ 返回 null 表示不过滤（等价全量）；
     * 无任何绑定 → 返回空集合（调用方必须据此查空，不得放开）。
     */
    public List<String> allowedEntities(String userName) {
        if (userName == null || userName.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<BiPermRule> bindings = permRuleDao.selectRowBindings(userName);
        List<String> out = new ArrayList<>();
        for (BiPermRule r : bindings) {
            if (r.getEntityId() == null) {
                continue;
            }
            if ("*".equals(r.getEntityId())) {
                return null;   // 全量
            }
            out.add(r.getEntityId());
        }
        return out;
    }

    /** 列掩码：命中 COL_MASK 规则即掩码；COL_SENSITIVE 登记但未命中规则 → 默认掩码（C-4.10-02） */
    public Object maskIfHit(String column, Object value) {
        if (value == null) {
            return null;
        }
        List<BiPermRule> rules = permRuleDao.selectColRules();
        Boolean hitMask = null;
        boolean sensitive = false;
        for (BiPermRule r : rules) {
            boolean hit = "*".equals(r.getTargetKey()) || column.equalsIgnoreCase(r.getTargetKey());
            if (BiPermRule.TYPE_COL_SENSITIVE.equals(r.getRuleType()) && hit) {
                sensitive = true;
            }
            if (BiPermRule.TYPE_COL_MASK.equals(r.getRuleType()) && hit
                    && "MASK".equals(r.getAction())) {
                hitMask = Boolean.TRUE;
            }
        }
        if (Boolean.TRUE.equals(hitMask) || sensitive) {
            String s = String.valueOf(value);
            if (s.length() <= 2) {
                return "**";
            }
            return s.charAt(0) + "***" + s.charAt(s.length() - 1);
        }
        return value;
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

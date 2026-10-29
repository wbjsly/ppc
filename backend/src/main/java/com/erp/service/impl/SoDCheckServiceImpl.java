package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.SodRuleDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.system.SodRule;
import com.erp.service.SoDCheckService;
import com.erp.service.system.PositionBaselineService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 职责分离预检实现（add-permission-approval-flow design D4）。
 * 冲突判定：目标×目标 ∪ 目标×现有 规范化（A&lt;B）后命中互斥矩阵；
 * 超基线判定复用 16.2 岗位基线（员工 POS_ID → 基线表）。
 */
@Service
@RequiredArgsConstructor
public class SoDCheckServiceImpl implements SoDCheckService {

    private final SodRuleDao ruleDao;
    private final SysUserRoleDao userRoleDao;
    private final PositionBaselineService baselineService;
    private final JdbcTemplate jdbc;

    @Override
    public Map<String, Object> check(String targetUserId, List<String> roleCodes) {
        String userId = StringUtils.hasText(targetUserId) ? targetUserId
                : SecurityUtils.getCurrentUserId();
        List<String> target = dedupe(roleCodes);
        List<String> existing = userId == null ? List.of()
                : userRoleDao.getUserRoleCodes(userId);
        List<String> baseline = baselineFor(userId);

        List<String> beyond = new ArrayList<>(target);
        beyond.removeAll(baseline);

        Set<String> pairs = new LinkedHashSet<>();
        for (int i = 0; i < target.size(); i++) {
            for (int j = i + 1; j < target.size(); j++) {
                pairs.add(normalize(target.get(i), target.get(j)));
            }
        }
        for (String t : target) {
            for (String e : existing) {
                if (!t.equals(e)) {
                    pairs.add(normalize(t, e));
                }
            }
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (!pairs.isEmpty()) {
            for (SodRule r : ruleDao.selectList(new LambdaQueryWrapper<SodRule>())) {
                String key = normalize(r.getRoleA(), r.getRoleB());
                if (pairs.contains(key)) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("roleA", r.getRoleA());
                    c.put("roleB", r.getRoleB());
                    c.put("remark", r.getRemark());
                    conflicts.add(c);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetUserId", userId);
        out.put("baseline", baseline);
        out.put("beyondBaseline", beyond);
        out.put("existing", existing);
        out.put("conflicts", conflicts);
        return out;
    }

    @Override
    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SodRule r : ruleDao.selectList(new LambdaQueryWrapper<SodRule>()
                .orderByAsc(SodRule::getRoleA).orderByAsc(SodRule::getRoleB))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("roleA", r.getRoleA());
            m.put("roleB", r.getRoleB());
            m.put("remark", r.getRemark());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRule(String roleA, String roleB, String remark) {
        if (!StringUtils.hasText(roleA) || !StringUtils.hasText(roleB)) {
            throw new ServiceException(422, "互斥角色对必填");
        }
        if (roleA.trim().equalsIgnoreCase(roleB.trim())) {
            throw new ServiceException(422, "同一角色不能自互斥");
        }
        String key = normalize(roleA.trim(), roleB.trim());
        String[] ab = key.split("\\|");
        // 判重两种序都查（防早期非规范序存量行漏判）
        Long dup = ruleDao.selectCount(new LambdaQueryWrapper<SodRule>()
                .and(w -> w.eq(SodRule::getRoleA, ab[0]).eq(SodRule::getRoleB, ab[1])
                        .or(o -> o.eq(SodRule::getRoleA, ab[1]).eq(SodRule::getRoleB, ab[0]))));
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "互斥规则已存在：" + ab[0] + "<->" + ab[1]);
        }
        SodRule r = new SodRule();
        r.setId(UUID.randomUUID().toString().replace("-", ""));
        r.setRoleA(ab[0]);
        r.setRoleB(ab[1]);
        r.setRemark(remark);
        ruleDao.insert(r);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(String id) {
        SodRule r = ruleDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "互斥规则不存在：" + id);
        }
        ruleDao.deleteById(id);
    }

    /** 用户岗位基线（无员工锚/无岗位 → 空） */
    private List<String> baselineFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<String> posIds = jdbc.queryForList(
                "SELECT POS_ID FROM hr_employee WHERE USER_ID = ? AND DEL_FLAG = '0' "
                        + "AND POS_ID IS NOT NULL LIMIT 1", String.class, userId);
        if (posIds.isEmpty()) {
            return List.of();
        }
        return baselineService.rolesOfPos(posIds.get(0));
    }

    /** 规范化角色对：字典序 A&lt;B，以 | 连接（角色码不含 |，键形态唯一） */
    private String normalize(String a, String b) {
        String x = a.toUpperCase();
        String y = b.toUpperCase();
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    private List<String> dedupe(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String c : codes) {
            if (StringUtils.hasText(c)) {
                seen.add(c.trim());
            }
        }
        return new ArrayList<>(seen);
    }
}

package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.GrantLedgerDao;
import com.erp.dao.system.SysRoleDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.system.GrantLedger;
import com.erp.entity.system.SysRole;
import com.erp.entity.system.SysUserRole;
import com.erp.service.ChangeWindowService;
import com.erp.service.GrantLedgerService;
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
import java.util.UUID;

/**
 * 授予台账实现（design D5/D6）。
 * activate：窗口校验 → 台账 → 投影 → 申请单回填 → 审计 → 通知，单事务整体回滚。
 * reclaim：幂等（非 ACTIVE 返回现态），快照 = 回收前该用户角色全集。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantLedgerServiceImpl implements GrantLedgerService {

    private final GrantLedgerDao ledgerDao;
    private final SysRoleDao roleDao;
    private final SysUserRoleDao userRoleDao;
    private final ChangeWindowService changeWindowService;
    private final com.erp.service.system.PositionBaselineService baselineService;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> activate(String applyId, String targetUserId, List<String> roleCodes,
                                        String source, LocalDateTime expireAt, boolean requireWindow,
                                        String operator) {
        if (!StringUtils.hasText(applyId) || !StringUtils.hasText(targetUserId)) {
            throw new ServiceException(422, "申请单与目标用户必填");
        }
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new ServiceException(422, "授予角色不能为空");
        }
        if (expireAt == null || !expireAt.isAfter(LocalDateTime.now())) {
            throw new ServiceException(422, "到期时间必须晚于当前时间");
        }
        if (requireWindow && !changeWindowService.isInWindow(LocalDateTime.now())) {
            throw new ServiceException(422,
                    "特权权限仅允许在变更窗口内生效，当前不在窗口，请等待窗口或改用指定时点");
        }

        List<String> grantIds = new ArrayList<>();
        List<String> activated = new ArrayList<>();
        for (String code : roleCodes) {
            // 幂等：同申请单同角色已有 ACTIVE 台账 → 跳过（重复生效防护）
            Long exist = ledgerDao.selectCount(new LambdaQueryWrapper<GrantLedger>()
                    .eq(GrantLedger::getApplyId, applyId)
                    .eq(GrantLedger::getRoleCode, code)
                    .eq(GrantLedger::getStatus, GrantLedger.ST_ACTIVE));
            if (exist != null && exist > 0) {
                continue;
            }
            SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, code).last("LIMIT 1"));
            if (role == null) {
                // 未知角色 → 抛出使台账/投影整体回滚（D5 失败整体回滚语义）
                throw new ServiceException(422, "角色不存在：" + code);
            }

            GrantLedger g = new GrantLedger();
            g.setId(uuid());
            g.setApplyId(applyId);
            g.setTargetUserId(targetUserId);
            g.setRoleCode(code);
            g.setSource(StringUtils.hasText(source) ? source : "NORMAL");
            g.setGrantAt(LocalDateTime.now());
            g.setExpireAt(expireAt);
            g.setStatus(GrantLedger.ST_ACTIVE);
            g.setCreateBy(operator);
            ledgerDao.insert(g);
            grantIds.add(g.getId());

            // 生效投影：user_role 已有绑定则跳过
            Integer bound = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_admin_user_role WHERE USER_ID=? AND ROLE_ID=?",
                    Integer.class, targetUserId, role.getId());
            if (bound == null || bound == 0) {
                SysUserRole ur = new SysUserRole();
                ur.setId(uuid());
                ur.setUserId(targetUserId);
                ur.setRoleId(role.getId());
                userRoleDao.insert(ur);
            }
            activated.add(code);
        }

        if (!activated.isEmpty()) {
            // 申请单回填（GRANT_ID 存首个台账行；状态 ACTIVE）
            jdbc.update("UPDATE erp_admin_perm_request SET STATUS='ACTIVE', GRANT_ID=?, "
                            + "UPDATE_DATE=NOW() WHERE ID=?",
                    grantIds.isEmpty() ? null : grantIds.get(0), applyId);
            // 审计（C-5.6-04 口径）
            jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, "
                            + "REASON, CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                    uuid(), targetUserId, "PERMISSION_GRANTED", "",
                    String.join(",", activated), "申请单 " + applyId, operator, LocalDateTime.now());
            // 站内通知：申请人 + 直属主管
            notice(targetUserId, "权限已生效",
                    "申请单 " + applyId + " 授予角色：" + String.join("、", activated)
                            + "，到期时间 " + expireAt, applyId, operator);
            String manager = managerOf(targetUserId);
            if (manager != null && !manager.equals(targetUserId)) {
                notice(manager, "下属权限生效通知",
                        "申请单 " + applyId + " 为用户 " + targetUserId + " 授予："
                                + String.join("、", activated), applyId, operator);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grantIds", grantIds);
        out.put("activatedRoles", activated);
        out.put("expireAt", expireAt);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reclaim(String ledgerId, String operator) {
        return reclaimInternal(ledgerId, operator, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reclaimForReview(String ledgerId, String operator) {
        return reclaimInternal(ledgerId, operator, true);
    }

    /**
     * @param bypassBaselineGuard true = UAR 复核权威收回（add-access-review-console D5），
     *                            基线内角色也删除投影
     */
    private Map<String, Object> reclaimInternal(String ledgerId, String operator,
                                                boolean bypassBaselineGuard) {
        GrantLedger g = ledgerDao.selectById(ledgerId);
        if (g == null) {
            throw new ServiceException(404, "台账行不存在：" + ledgerId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledgerId", ledgerId);
        if (!GrantLedger.ST_ACTIVE.equals(g.getStatus())) {
            // 幂等：非 ACTIVE 返回现态
            out.put("reclaimed", false);
            out.put("status", g.getStatus());
            return out;
        }

        // 回收前权限快照（该用户当时角色全集）
        List<String> current = userRoleDao.getUserRoleCodes(g.getTargetUserId());
        String snapshot;
        try {
            snapshot = MAPPER.writeValueAsString(current);
        } catch (Exception e) {
            snapshot = "[]";
        }

        // 删生效投影（仅该角色绑定）；例外申请全集落台账——若角色仍在岗位基线内，
        // 到期后基线授权存续，不删投影（design D6）。
        // BG 豁免（add-break-glass-console D3）：BREAK_GLASS 来源回收即删，不受守卫限制。
        SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, g.getRoleCode()).last("LIMIT 1"));
        List<String> baseline = baselineOf(g.getTargetUserId());
        if (role != null && (bypassBaselineGuard || !baseline.contains(g.getRoleCode())
                || "BREAK_GLASS".equals(g.getSource()))) {
            jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID=? AND ROLE_ID=?",
                    g.getTargetUserId(), role.getId());
        }

        g.setStatus(GrantLedger.ST_RECLAIMED);
        g.setReclaimAt(LocalDateTime.now());
        g.setSnapshotJson(snapshot);
        g.setUpdateBy(operator);
        g.setUpdateDate(LocalDateTime.now());
        ledgerDao.updateById(g);

        // 申请单回写 EXPIRED（仍为 ACTIVE 的常规回收场景）
        jdbc.update("UPDATE erp_admin_perm_request SET STATUS='EXPIRED', UPDATE_DATE=NOW() "
                + "WHERE ID=? AND STATUS='ACTIVE'", g.getApplyId());

        // 审计
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, "
                        + "REASON, CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), g.getTargetUserId(), "PERMISSION_RECLAIMED", snapshot,
                g.getRoleCode(), "到期回收 台账 " + ledgerId, operator, LocalDateTime.now());
        notice(g.getTargetUserId(), "权限已到期回收",
                "角色 " + g.getRoleCode() + " 已到期回收（申请单 " + g.getApplyId()
                        + "），如需继续使用请重新提交权限申请", g.getApplyId(), operator);

        out.put("reclaimed", true);
        out.put("status", GrantLedger.ST_RECLAIMED);
        out.put("snapshot", snapshot);
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String status) {
        String sql = "SELECT l.ID AS id, l.APPLY_ID AS applyId, l.TARGET_USER_ID AS targetUserId, "
                + "u.USERNAME AS username, l.ROLE_CODE AS roleCode, r.ROLE_NAME AS roleName, "
                + "l.SOURCE AS source, l.GRANT_AT AS grantAt, l.EXPIRE_AT AS expireAt, "
                + "l.STATUS AS status, l.RECLAIM_AT AS reclaimAt, l.REMIND_AT AS remindAt "
                + "FROM erp_admin_grant_ledger l "
                + "LEFT JOIN erp_admin_user u ON u.ID = l.TARGET_USER_ID "
                + "LEFT JOIN erp_admin_role r ON r.ROLE_CODE = l.ROLE_CODE "
                + "WHERE l.DEL_FLAG = '0' "
                + (StringUtils.hasText(status) ? "AND l.STATUS = ? " : "")
                + "ORDER BY l.EXPIRE_AT ASC";
        if (StringUtils.hasText(status)) {
            return jdbc.queryForList(sql, status);
        }
        return jdbc.queryForList(sql);
    }

    private void notice(String userId, String title, String content, String bizId, String operator) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                            + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?,?, '0', ?, NOW(), '0', 0)",
                    uuid(), userId, title, content, "PermRequest", bizId, operator);
        } catch (Exception e) {
            log.warn("notice write failed: {}", e.getMessage());
        }
    }

    /** 直属主管账号（REPORT_TO 链）；无则 null */
    private String managerOf(String userId) {
        List<String> mgr = jdbc.queryForList(
                "SELECT e2.USER_ID FROM hr_employee e1 "
                        + "JOIN hr_employee e2 ON e1.REPORT_TO_EMP_ID = e2.ID "
                        + "WHERE e1.USER_ID = ? AND e1.DEL_FLAG = '0' AND e2.USER_ID IS NOT NULL "
                        + "LIMIT 1", String.class, userId);
        return mgr.isEmpty() ? null : mgr.get(0);
    }

    /** 用户岗位基线角色（无锚 → 空） */
    private List<String> baselineOf(String userId) {
        List<String> posIds = jdbc.queryForList(
                "SELECT POS_ID FROM hr_employee WHERE USER_ID = ? AND DEL_FLAG = '0' "
                        + "AND POS_ID IS NOT NULL LIMIT 1", String.class, userId);
        if (posIds.isEmpty()) {
            return List.of();
        }
        return baselineService.rolesOfPos(posIds.get(0));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

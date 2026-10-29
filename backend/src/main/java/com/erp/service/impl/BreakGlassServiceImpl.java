package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.BgAuditDao;
import com.erp.dao.system.BgIncidentDao;
import com.erp.dao.system.BgRequestDao;
import com.erp.dao.system.BgTemplateDao;
import com.erp.entity.system.BgAudit;
import com.erp.entity.system.BgIncident;
import com.erp.entity.system.BgRequest;
import com.erp.entity.system.BgTemplate;
import com.erp.service.BreakGlassService;
import com.erp.service.GrantLedgerService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.system.PrivilegedRoles;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 紧急权限实现（add-break-glass-console design D1-D7，SOP-5.6-C）。
 * 双签 = 单 SEQ 组内 joint(ROLE_DUTY_MGR) + joint(ROLE_ADMIN)；
 * 生效豁免变更窗口（BG 即窗口外合规通道）；回收豁免基线守卫（回收即删）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BreakGlassServiceImpl implements BreakGlassService {

    public static final String BIZ_BREAK_GLASS = "BreakGlassRequest";
    public static final String SOURCE_BREAK_GLASS = "BREAK_GLASS";

    private final BgIncidentDao incidentDao;
    private final BgTemplateDao templateDao;
    private final BgRequestDao requestDao;
    private final BgAuditDao auditDao;
    private final ApprovalEngine approvalEngine;
    private final GrantLedgerService grantLedgerService;
    private final JdbcTemplate jdbc;

    @Value("${app.admin.break-glass-max-hours:2}")
    private int maxHours;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 故障工单 ----------

    @Override
    public List<Map<String, Object>> incidentList(String status) {
        String sql = "SELECT ID AS id, INC_NO AS incNo, TITLE AS title, STATUS AS status, "
                + "HANDLER AS handler, CREATE_DATE AS createDate FROM erp_ops_incident "
                + "WHERE DEL_FLAG = '0' "
                + (StringUtils.hasText(status) ? "AND STATUS = ? " : "")
                + "ORDER BY CREATE_DATE DESC";
        if (StringUtils.hasText(status)) {
            return jdbc.queryForList(sql, status);
        }
        return jdbc.queryForList(sql);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> incidentSave(Map<String, Object> body) {
        String incNo = str(body.get("incNo"));
        String title = str(body.get("title"));
        if (!StringUtils.hasText(incNo) || !StringUtils.hasText(title)) {
            throw new ServiceException(422, "工单编号与标题必填");
        }
        BgIncident inc = new BgIncident();
        inc.setIncNo(incNo.trim());
        inc.setTitle(title.trim());
        inc.setStatus(BgIncident.ST_PROCESSING);
        inc.setHandler(str(body.get("handler")));
        inc.setCreateBy(SecurityUtils.getCurrentUserId());
        incidentDao.insert(inc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", inc.getId());
        out.put("incNo", inc.getIncNo());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void incidentTransition(String id, String status) {
        if (!BgIncident.ST_RESOLVED.equals(status) && !BgIncident.ST_CLOSED.equals(status)) {
            throw new ServiceException(422, "工单状态仅支持 RESOLVED/CLOSED");
        }
        BgIncident inc = incidentDao.selectById(id);
        if (inc == null) {
            throw new ServiceException(404, "工单不存在：" + id);
        }
        inc.setStatus(status);
        inc.setUpdateBy(SecurityUtils.getCurrentUserId());
        inc.setUpdateDate(LocalDateTime.now());
        incidentDao.updateById(inc);
    }

    // ---------- 权限模板 ----------

    @Override
    public List<Map<String, Object>> templateList() {
        return jdbc.queryForList(
                "SELECT ID AS id, CODE AS code, NAME AS name, ROLES_JSON AS rolesJson, "
                        + "SCENARIO AS scenario, STATUS AS status FROM erp_admin_bg_template "
                        + "WHERE DEL_FLAG = '0' ORDER BY CODE");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> templateSave(Map<String, Object> body) {
        String code = str(body.get("code"));
        String name = str(body.get("name"));
        String rolesJson = str(body.get("rolesJson"));
        if (!StringUtils.hasText(code) || !StringUtils.hasText(name) || !StringUtils.hasText(rolesJson)) {
            throw new ServiceException(422, "模板编码/名称/角色集合必填");
        }
        List<String> roles;
        try {
            roles = MAPPER.readValue(rolesJson, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new ServiceException(422, "角色集合须为 JSON 数组");
        }
        if (roles.isEmpty()) {
            throw new ServiceException(422, "角色集合不能为空（最小可用集合）");
        }
        // D6：模板角色集合禁特权角色（L1 硬阻断）
        List<String> bad = roles.stream().filter(PrivilegedRoles::contains).toList();
        if (!bad.isEmpty()) {
            throw new ServiceException(422, "模板角色集合含特权角色，禁止作为紧急权限模板：" + bad);
        }
        BgTemplate t = new BgTemplate();
        t.setCode(code.trim());
        t.setName(name.trim());
        try {
            t.setRolesJson(MAPPER.writeValueAsString(roles));
        } catch (Exception e) {
            throw new ServiceException(500, "角色集合序列化失败");
        }
        t.setScenario(str(body.get("scenario")));
        String status = str(body.get("status"));
        t.setStatus("0".equals(status) ? "0" : "1");
        t.setCreateBy(SecurityUtils.getCurrentUserId());
        templateDao.insert(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("code", t.getCode());
        return out;
    }

    // ---------- 申请与审批 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String targetUserId, String templateId, String incidentId,
                                      Integer durationHours, String screenDeclare, String reason) {
        if (!StringUtils.hasText(targetUserId)) {
            throw new ServiceException(422, "目标用户必填");
        }
        // BR-5.6-05：工单存在且处理中（L1 硬阻断）
        if (!StringUtils.hasText(incidentId)) {
            throw new ServiceException(422, "须关联有效故障工单");
        }
        BgIncident inc = incidentDao.selectById(incidentId);
        if (inc == null) {
            throw new ServiceException(422, "须关联有效故障工单（工单不存在）");
        }
        if (!BgIncident.ST_PROCESSING.equals(inc.getStatus())) {
            throw new ServiceException(422, "须关联状态为处理中的故障工单，当前工单状态：" + inc.getStatus());
        }
        // 模板启用 + 角色快照
        if (!StringUtils.hasText(templateId)) {
            throw new ServiceException(422, "紧急权限模板必填");
        }
        BgTemplate tpl = templateDao.selectById(templateId);
        if (tpl == null) {
            throw new ServiceException(422, "模板不存在：" + templateId);
        }
        if (!"1".equals(tpl.getStatus())) {
            throw new ServiceException(422, "模板已停用：" + tpl.getCode());
        }
        List<String> roles;
        try {
            roles = MAPPER.readValue(tpl.getRolesJson(), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new ServiceException(500, "模板角色集合损坏");
        }
        // 时长 1..maxHours
        int hours = durationHours == null ? 2 : durationHours;
        if (hours < 1 || hours > maxHours) {
            throw new ServiceException(422, "申请时长须为 1-" + maxHours + " 小时（参数 BREAK_GLASS_MAX_HOURS）");
        }
        // 录屏声明必选
        if (!"YES".equals(screenDeclare) && !"NO".equals(screenDeclare)) {
            throw new ServiceException(422, "录屏声明必选（YES/NO）");
        }
        // 处置说明 ≥10 字
        if (!StringUtils.hasText(reason) || reason.trim().length() < 10) {
            throw new ServiceException(422, "处置说明必填且不少于 10 字");
        }
        // 同目标用户 PENDING 去重
        Long pend = requestDao.selectCount(new LambdaQueryWrapper<BgRequest>()
                .eq(BgRequest::getTargetUserId, targetUserId)
                .eq(BgRequest::getStatus, BgRequest.ST_PENDING));
        if (pend != null && pend > 0) {
            throw new ServiceException(422, "该用户已有待审批的紧急权限申请，勿重复提交");
        }

        String applicant = SecurityUtils.getCurrentUserId();
        BgRequest req = new BgRequest();
        req.setId(uuid());
        req.setTargetUserId(targetUserId);
        req.setApplicantUserId(applicant);
        req.setTemplateId(tpl.getId());
        try {
            req.setRolesJson(MAPPER.writeValueAsString(roles));
        } catch (Exception e) {
            throw new ServiceException(500, "角色集合序列化失败");
        }
        req.setIncidentId(inc.getId());
        req.setDurationHours(hours);
        req.setScreenDeclare(screenDeclare);
        req.setReason(reason.trim());
        req.setStatus(BgRequest.ST_PENDING);
        req.setCreateBy(applicant);
        requestDao.insert(req);

        // D2：双签单 SEQ 组（值班经理 + 安全管理员）；escalateTo = 运维负责人（CCB 运维角色复用）
        List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                ApprovalNodeSpec.joint("ROLE_DUTY_MGR", "值班经理会签"),
                ApprovalNodeSpec.joint("ROLE_ADMIN", "安全管理员会签")));
        String title = "Break-Glass 紧急权限[" + tpl.getCode() + "] " + hours + "h → "
                + username(targetUserId) + "（工单 " + inc.getIncNo() + "）";
        var inst = approvalEngine.submit(BIZ_BREAK_GLASS, req.getId(), title,
                "ROLE_CCB_OPS", chain);
        req.setApprInstanceId(inst.getId());
        requestDao.updateById(req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", req.getId());
        out.put("status", req.getStatus());
        out.put("approvalInstanceId", inst.getId());
        return out;
    }

    @Override
    public List<Map<String, Object>> requestList(String status) {
        String sql = "SELECT r.ID AS id, r.TARGET_USER_ID AS targetUserId, "
                + "u.USERNAME AS targetUsername, r.TEMPLATE_ID AS templateId, "
                + "t.CODE AS templateCode, t.NAME AS templateName, r.ROLES_JSON AS rolesJson, "
                + "i.INC_NO AS incNo, i.TITLE AS incidentTitle, r.DURATION_HOURS AS durationHours, "
                + "r.SCREEN_DECLARE AS screenDeclare, r.REASON AS reason, r.STATUS AS status, "
                + "r.GRANT_ID AS grantId, r.RECLAIM_AT AS reclaimAt, r.CREATE_DATE AS createDate "
                + "FROM erp_admin_bg_request r "
                + "LEFT JOIN erp_admin_user u ON u.ID = r.TARGET_USER_ID "
                + "LEFT JOIN erp_admin_bg_template t ON t.ID = r.TEMPLATE_ID "
                + "LEFT JOIN erp_ops_incident i ON i.ID = r.INCIDENT_ID "
                + "WHERE r.DEL_FLAG = '0' "
                + (StringUtils.hasText(status) ? "AND r.STATUS = ? " : "")
                + "ORDER BY r.CREATE_DATE DESC";
        if (StringUtils.hasText(status)) {
            return jdbc.queryForList(sql, status);
        }
        return jdbc.queryForList(sql);
    }

    @Override
    public Map<String, Object> detail(String id) {
        BgRequest req = requestDao.selectById(id);
        if (req == null) {
            throw new ServiceException(404, "申请单不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", req);
        out.put("targetUsername", username(req.getTargetUserId()));
        out.put("approvalLogs", approvalEngine.logs(BIZ_BREAK_GLASS, id));
        return out;
    }

    // ---------- 回收 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reclaim(String id) {
        BgRequest req = requestDao.selectById(id);
        if (req == null) {
            throw new ServiceException(404, "申请单不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (!BgRequest.ST_ACTIVE.equals(req.getStatus())) {
            out.put("reclaimed", false);
            out.put("status", req.getStatus());
            return out;
        }
        reclaimLedger(req.getGrantId());
        out.put("reclaimed", true);
        out.put("status", BgRequest.ST_RECLAIMED);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reclaimLedger(String ledgerId) {
        if (!StringUtils.hasText(ledgerId)) {
            throw new ServiceException(422, "台账行必填");
        }
        String operator = SecurityUtils.getCurrentUserId();
        if (!StringUtils.hasText(operator)) {
            operator = "bg-sweep";
        }
        // 回收（豁免基线守卫在 GrantLedgerServiceImpl 内按 SOURCE 分支）
        grantLedgerService.reclaim(ledgerId, operator);
        // 申请单回写 RECLAIMED（BG 台账行唯一对应模板首个角色）
        jdbc.update("UPDATE erp_admin_bg_request SET STATUS=?, RECLAIM_AT=NOW(), UPDATE_DATE=NOW() "
                        + "WHERE GRANT_ID=? AND STATUS=?",
                BgRequest.ST_RECLAIMED, ledgerId, BgRequest.ST_ACTIVE);
        // 回收即生成事后审计（D4）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ID, TARGET_USER_ID, APPLICANT_USER_ID, GRANT_ID "
                        + "FROM erp_admin_bg_request WHERE GRANT_ID=?", ledgerId);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            BgRequest req = new BgRequest();
            req.setId(String.valueOf(row.get("ID")));
            req.setTargetUserId(String.valueOf(row.get("TARGET_USER_ID")));
            req.setApplicantUserId(String.valueOf(row.get("APPLICANT_USER_ID")));
            req.setGrantId(String.valueOf(row.get("GRANT_ID")));
            generateAudit(req, ledgerId);
        }
    }

    // ---------- 事后审计 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateAudit(BgRequest req, String ledgerId) {
        // 幂等：LEDGER_ID 唯一锚
        Long exist = auditDao.selectCount(new LambdaQueryWrapper<BgAudit>()
                .eq(BgAudit::getLedgerId, ledgerId));
        if (exist != null && exist > 0) {
            return;
        }
        Map<String, Object> led = jdbc.queryForMap(
                "SELECT GRANT_AT, RECLAIM_AT FROM erp_admin_grant_ledger WHERE ID=?", ledgerId);
        LocalDateTime grantAt = (LocalDateTime) led.get("GRANT_AT");
        LocalDateTime reclaimAt = (LocalDateTime) led.get("RECLAIM_AT");
        // 期间目标账号审计日志条数（hr_change_log，C-0-04 全业务写操作留痕口径）
        Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND CHANGE_AT >= ? "
                        + "AND CHANGE_AT <= ?", Integer.class, req.getTargetUserId(),
                grantAt == null ? LocalDateTime.now().minusDays(1) : grantAt,
                reclaimAt == null ? LocalDateTime.now() : reclaimAt);
        // 月度 ≥3 次专项评审（按申请人当月 ACTIVE/RECLAIMED/AUDITED 单数）
        Integer monthCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_bg_request WHERE APPLICANT_USER_ID=? "
                        + "AND DEL_FLAG='0' AND STATUS IN ('ACTIVE','RECLAIMED','AUDITED') "
                        + "AND CREATE_DATE >= DATE_FORMAT(NOW(), '%Y-%m-01')",
                Integer.class, req.getApplicantUserId());

        BgAudit audit = new BgAudit();
        audit.setId(uuid());
        audit.setApplyId(req.getId());
        audit.setLedgerId(ledgerId);
        audit.setTargetUserId(req.getTargetUserId());
        audit.setGrantAt(grantAt);
        audit.setReclaimAt(reclaimAt);
        audit.setLogCount(logCount == null ? 0 : logCount);
        audit.setSpecialReview(monthCount != null && monthCount >= 3 ? "1" : "0");
        audit.setCreateBy("bg-sweep");
        auditDao.insert(audit);

        // 通知安全管理员（ROLE_ADMIN 持有者）报告已生成待签署
        for (String admin : roleHolders("ROLE_ADMIN")) {
            notice(admin, "Break-Glass 事后审计待签署",
                    "申请单 " + req.getId() + " 的紧急权限已回收，请核验并签署事后审计报告",
                    req.getId(), "bg-sweep");
        }
    }

    @Override
    public List<Map<String, Object>> auditList() {
        return jdbc.queryForList(
                "SELECT a.ID AS id, a.APPLY_ID AS applyId, a.LEDGER_ID AS ledgerId, "
                        + "a.TARGET_USER_ID AS targetUserId, u.USERNAME AS targetUsername, "
                        + "a.GRANT_AT AS grantAt, a.RECLAIM_AT AS reclaimAt, a.LOG_COUNT AS logCount, "
                        + "a.CONCLUSION AS conclusion, a.SIGNED_BY AS signedBy, "
                        + "sb.NICK_NAME AS signedByName, a.SIGNED_AT AS signedAt, "
                        + "a.SPECIAL_REVIEW AS specialReview "
                        + "FROM erp_admin_bg_audit a "
                        + "LEFT JOIN erp_admin_user u ON u.ID = a.TARGET_USER_ID "
                        + "LEFT JOIN erp_admin_user sb ON sb.ID = a.SIGNED_BY "
                        + "WHERE a.DEL_FLAG = '0' ORDER BY a.CREATE_DATE DESC");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signAudit(String id, String conclusion) {
        if (!BgAudit.CONCLUSION_NO_VIOLATION.equals(conclusion)
                && !BgAudit.CONCLUSION_VIOLATION.equals(conclusion)) {
            throw new ServiceException(422, "签署结论仅支持 NO_VIOLATION/VIOLATION");
        }
        BgAudit audit = auditDao.selectById(id);
        if (audit == null) {
            throw new ServiceException(404, "审计报告不存在：" + id);
        }
        if (audit.getSignedAt() != null) {
            throw new ServiceException(422, "报告已签署，不可重复签署");
        }
        String me = SecurityUtils.getCurrentUserId();
        audit.setConclusion(conclusion);
        audit.setSignedBy(me);
        audit.setSignedAt(LocalDateTime.now());
        audit.setUpdateBy(me);
        audit.setUpdateDate(LocalDateTime.now());
        auditDao.updateById(audit);
        // 申请单置 AUDITED
        jdbc.update("UPDATE erp_admin_bg_request SET STATUS=?, UPDATE_DATE=NOW() WHERE ID=? "
                + "AND STATUS IN ('RECLAIMED','ACTIVE')", BgRequest.ST_AUDITED, audit.getApplyId());
        if (BgAudit.CONCLUSION_VIOLATION.equals(conclusion)) {
            for (String admin : roleHolders("ROLE_ADMIN")) {
                notice(admin, "Break-Glass 审计发现越权待查",
                        "报告 " + id + " 结论为有越权待查，请冻结相关权限并升级内审", audit.getApplyId(), me);
            }
        }
    }

    @Override
    public Map<String, Object> sweepAuditOverdue() {
        LocalDateTime now = LocalDateTime.now();
        // 回收超 24h 未签 → 每 12h 提醒（LAST_REMIND_AT 幂等）
        List<Map<String, Object>> overdue = jdbc.queryForList(
                "SELECT ID, APPLY_ID, TARGET_USER_ID, LAST_REMIND_AT FROM erp_admin_bg_audit "
                        + "WHERE DEL_FLAG='0' AND SIGNED_AT IS NULL AND RECLAIM_AT IS NOT NULL "
                        + "AND RECLAIM_AT <= ? AND (LAST_REMIND_AT IS NULL OR LAST_REMIND_AT <= ?)",
                now.minusHours(24), now.minusHours(12));
        int reminded = 0;
        for (Map<String, Object> row : overdue) {
            jdbc.update("UPDATE erp_admin_bg_audit SET LAST_REMIND_AT=?, UPDATE_DATE=NOW() WHERE ID=?",
                    now, row.get("ID"));
            String content = "Break-Glass 事后审计报告（申请单 " + row.get("APPLY_ID")
                    + "）回收后 24 小时仍未签署，请安全管理员尽快核验签署（BR-5.6-08）";
            for (String admin : roleHolders("ROLE_ADMIN")) {
                notice(admin, "Break-Glass 事后审计逾期未签署", content,
                        String.valueOf(row.get("APPLY_ID")), "bg-audit-sweep");
            }
            reminded++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        return out;
    }

    // ---------- 工具 ----------

    private List<String> roleHolders(String roleCode) {
        return jdbc.queryForList(
                "SELECT u.ID FROM erp_admin_user u JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE = ? AND u.STATUS = '1'", String.class, roleCode);
    }

    private void notice(String userId, String title, String content, String bizId, String operator) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                            + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?,?, '0', ?, NOW(), '0', 0)",
                    uuid(), userId, title, content, BIZ_BREAK_GLASS, bizId, operator);
        } catch (Exception e) {
            log.warn("bg notice write failed: {}", e.getMessage());
        }
    }

    private String username(String userId) {
        List<String> names = jdbc.queryForList(
                "SELECT USERNAME FROM erp_admin_user WHERE ID = ?", String.class, userId);
        return names.isEmpty() ? userId : names.get(0);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

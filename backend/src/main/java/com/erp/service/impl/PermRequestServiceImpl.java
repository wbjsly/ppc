package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.PermRequestDao;
import com.erp.entity.system.PermRequest;
import com.erp.service.ChangeWindowService;
import com.erp.service.PermRequestService;
import com.erp.service.SoDCheckService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.system.PrivilegedRoles;
import com.erp.service.system.SensitiveRoles;
import com.erp.util.SecurityUtils;
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
 * 权限申请实现（add-permission-approval-flow design D2/D4，SOP-5.6-B step1-3）。
 * 链构建：SEQ1 按人直属主管（自批上移）→ SEQ2 敏感数据 Owner → SEQ3 特权安全管理员会签
 * → SoD 合规官 → 跨法人法人授权人；提交时强制复检存快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermRequestServiceImpl implements PermRequestService {

    public static final String BIZ_PERM_REQUEST = "PermRequest";

    private final PermRequestDao requestDao;
    private final SoDCheckService soDCheckService;
    private final ApprovalEngine approvalEngine;
    private final ChangeWindowService changeWindowService;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitNormal(String targetUserId, List<String> roleCodes,
                                            String dataScope, String reason, String ticketNo,
                                            Integer validMonths, String activationMode,
                                            LocalDateTime activateAt) {
        List<String> roles = requireRoles(roleCodes);
        requireText(dataScope, "数据范围（法人主体/组织单元/部门）必填");
        requireReason(reason);
        if (validMonths == null || validMonths < 1 || validMonths > 12) {
            throw new ServiceException(422, "期望有效期须为 1-12 个月");
        }
        String risk = riskOf(roles);
        if (PermRequest.RISK_PRIVILEGED.equals(risk) && !StringUtils.hasText(ticketNo)) {
            throw new ServiceException(422, "特权权限申请须附故障单或项目立项编号");
        }
        String mode = StringUtils.hasText(activationMode) ? activationMode : PermRequest.MODE_IMMEDIATE;
        if (!PermRequest.MODE_IMMEDIATE.equals(mode) && !PermRequest.MODE_SCHEDULED.equals(mode)) {
            throw new ServiceException(422, "生效策略仅支持 IMMEDIATE/SCHEDULED");
        }
        if (PermRequest.MODE_SCHEDULED.equals(mode)) {
            if (activateAt == null || !activateAt.isAfter(LocalDateTime.now())) {
                throw new ServiceException(422, "指定时点须晚于当前时间");
            }
        } else {
            activateAt = null;
        }
        requireNoPending(targetUserId);

        PermRequest req = newRequest(PermRequest.KIND_NORMAL, targetUserId, roles, dataScope,
                reason.trim(), StringUtils.hasText(ticketNo) ? ticketNo.trim() : null,
                validMonths, mode, activateAt, risk);
        return submitWithApproval(req, roles);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitException(String targetUserId, List<String> targetRoles,
                                               List<String> beyondRoles, String reason) {
        List<String> roles = requireRoles(targetRoles);
        requireReason(reason);
        requireNoPending(targetUserId);
        // 例外申请单：数据范围留痕 = 例外授予语义（16.2 调整无范围概念，偏差口径见 proposal）
        PermRequest req = newRequest(PermRequest.KIND_EXCEPTION, targetUserId, roles,
                "例外授予（岗位基线外）", reason.trim(), null, 12,
                PermRequest.MODE_IMMEDIATE, null, riskOf(roles));
        Map<String, Object> out = submitWithApproval(req, roles);
        out.put("beyond", beyondRoles == null ? List.of() : beyondRoles);
        return out;
    }

    private Map<String, Object> submitWithApproval(PermRequest req, List<String> roles) {
        // 强制复检并存快照（SOP step2：提交瞬间复检，防规则变更窗口）
        Map<String, Object> precheck = soDCheckService.check(req.getTargetUserId(), roles);
        try {
            req.setSodSnapshotJson(MAPPER.writeValueAsString(precheck));
        } catch (Exception e) {
            throw new ServiceException(500, "预检快照序列化失败");
        }
        req.setStatus(PermRequest.ST_PENDING);
        req.setApplicantUserId(SecurityUtils.getCurrentUserId());
        requestDao.insert(req);

        List<List<ApprovalNodeSpec>> chain = buildChain(req, roles, precheck);
        String title = "权限申请[" + req.getRiskLevel() + "]：" + String.join("/", roles)
                + " → " + username(req.getTargetUserId());
        var inst = approvalEngine.submit(BIZ_PERM_REQUEST, req.getId(), title, null, chain);
        req.setApprInstanceId(inst.getId());
        requestDao.updateById(req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", req.getId());
        out.put("kind", req.getKind());
        out.put("status", req.getStatus());
        out.put("riskLevel", req.getRiskLevel());
        out.put("outWindow", req.getOutWindow() == null ? "0" : req.getOutWindow());
        out.put("approvalInstanceId", inst.getId());
        out.put("beyondBaseline", precheck.get("beyondBaseline"));
        out.put("conflicts", precheck.get("conflicts"));
        return out;
    }

    /** D2 风险路由链 */
    private List<List<ApprovalNodeSpec>> buildChain(PermRequest req, List<String> roles,
                                                    Map<String, Object> precheck) {
        List<List<ApprovalNodeSpec>> groups = new ArrayList<>();
        String applicant = req.getApplicantUserId();

        // SEQ1 直属主管（按人节点；自批上移；无账号回落部门负责人）
        groups.add(List.of(managerNode(req.getTargetUserId(), applicant)));

        // SEQ2 敏感数据 Owner（去重，可多节点并行）
        List<String> owners = SensitiveRoles.hitting(roles).stream()
                .map(SensitiveRoles::ownerOf).distinct().toList();
        if (!owners.isEmpty()) {
            List<ApprovalNodeSpec> ownerNodes = new ArrayList<>();
            for (String owner : owners) {
                ownerNodes.add(ApprovalNodeSpec.sign(owner, "数据 Owner（" + owner + "）"));
            }
            groups.add(ownerNodes);
        }

        // SEQ3 特权 → 安全管理员会签
        if (roles.stream().anyMatch(PrivilegedRoles::contains)) {
            groups.add(List.of(ApprovalNodeSpec.joint("ROLE_ADMIN", "安全管理员会签")));
        }

        // SoD 命中 → 合规官（BR-5.6-02，位于生效之前）
        if (!conflictsOf(precheck).isEmpty()) {
            groups.add(List.of(ApprovalNodeSpec.sign("ROLE_COMPLIANCE", "合规官（SoD 复核）")));
        }

        // 跨法人 → 法人授权人（无模型，兜底 ADMIN，偏差表 2）
        if (crossLegal(req.getTargetUserId(), applicant)) {
            groups.add(List.of(ApprovalNodeSpec.sign("ROLE_ADMIN", "法人授权人")));
        }

        // 窗口外 + 普通/敏感 → CCB 三方会签（add-change-window-console D1/D2，
        // C-5.6-03 / BR-5.6-04）；特权不追加（维持窗口外挂起等窗口，双轨 D5）
        if (!PermRequest.RISK_PRIVILEGED.equals(req.getRiskLevel())
                && !changeWindowService.isInWindow(LocalDateTime.now())) {
            req.setOutWindow("1");
            groups.add(List.of(
                    ApprovalNodeSpec.sign("ROLE_CCB_OPS", "CCB 运维负责人"),
                    ApprovalNodeSpec.sign("ROLE_CCB_ARCH", "CCB 架构委员会代表"),
                    ApprovalNodeSpec.sign("ROLE_CCB_BIZ", "CCB 业务 Owner")));
        }
        return groups;
    }

    /**
     * 直属主管节点（BR-5.6-01 构建层自批防线）：
     * 主管有账号且 ≠ 申请人 → 按人节点；主管=申请人 → 上移至主管的主管；
     * 上移也无 → ROLE_ADMIN 回落；主管无账号/无汇报 → ROLE_DEPT_HEAD 回落。
     */
    private ApprovalNodeSpec managerNode(String targetUserId, String applicant) {
        Map<String, String> m1 = directManager(targetUserId);
        String m1User = m1.get("userId");
        if (m1User == null) {
            return ApprovalNodeSpec.sign("ROLE_DEPT_HEAD", "部门负责人（主管缺位回落）");
        }
        if (!m1User.equals(applicant)) {
            return ApprovalNodeSpec.person(m1User, "直属主管（" + m1.get("name") + "）");
        }
        Map<String, String> m2 = directManager(m1User);
        String m2User = m2.get("userId");
        if (m2User != null && !m2User.equals(applicant)) {
            return ApprovalNodeSpec.person(m2User, "上级主管（" + m2.get("name") + "）");
        }
        return ApprovalNodeSpec.sign("ROLE_ADMIN", "上级审批（主管自申请回落）");
    }

    /** 直属主管（REPORT_TO → 主管账号）；无则 {userId:null} */
    private Map<String, String> directManager(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e2.USER_ID AS uid, e2.NAME AS name FROM hr_employee e1 "
                        + "JOIN hr_employee e2 ON e1.REPORT_TO_EMP_ID = e2.ID "
                        + "WHERE e1.USER_ID = ? AND e1.DEL_FLAG = '0' AND e2.DEL_FLAG = '0' "
                        + "AND e2.USER_ID IS NOT NULL AND e2.USER_ID <> '' LIMIT 1", userId);
        Map<String, String> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("userId", null);
            return out;
        }
        out.put("userId", String.valueOf(rows.get(0).get("uid")));
        out.put("name", rows.get(0).get("name") == null ? "主管" : String.valueOf(rows.get(0).get("name")));
        return out;
    }

    /** 跨法人主体判定（目标与申请人 LEGAL_ENTITY 均有值且不同） */
    private boolean crossLegal(String targetUserId, String applicantUserId) {
        if (!StringUtils.hasText(applicantUserId)) {
            return false;
        }
        List<String> entities = jdbc.queryForList(
                "SELECT LEGAL_ENTITY FROM hr_employee WHERE USER_ID IN (?,?) AND DEL_FLAG='0' "
                        + "AND LEGAL_ENTITY IS NOT NULL AND LEGAL_ENTITY <> ''",
                String.class, targetUserId, applicantUserId);
        return entities.size() >= 2 && !entities.get(0).equals(entities.get(1));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conflictsOf(Map<String, Object> precheck) {
        Object c = precheck.get("conflicts");
        return c instanceof List ? (List<Map<String, Object>>) c : List.of();
    }

    private PermRequest newRequest(String kind, String targetUserId, List<String> roles,
                                   String dataScope, String reason, String ticketNo,
                                   Integer validMonths, String mode, LocalDateTime activateAt,
                                   String risk) {
        PermRequest req = new PermRequest();
        req.setId(uuid());
        req.setKind(kind);
        req.setTargetUserId(targetUserId);
        try {
            req.setRolesJson(MAPPER.writeValueAsString(roles));
        } catch (Exception e) {
            throw new ServiceException(500, "角色集合序列化失败");
        }
        req.setRiskLevel(risk);
        req.setDataScope(dataScope);
        req.setReason(reason);
        req.setTicketNo(ticketNo);
        req.setValidMonths(validMonths);
        req.setActivationMode(mode);
        req.setActivateAt(activateAt);
        req.setCreateBy(SecurityUtils.getCurrentUserId());
        return req;
    }

    @Override
    public List<Map<String, Object>> list(String status, String keyword, String outWindow) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.ID AS id, r.KIND AS kind, r.TARGET_USER_ID AS targetUserId, "
                        + "u.USERNAME AS targetUsername, r.APPLICANT_USER_ID AS applicantUserId, "
                        + "r.ROLES_JSON AS rolesJson, r.RISK_LEVEL AS riskLevel, "
                        + "r.DATA_SCOPE AS dataScope, r.REASON AS reason, r.TICKET_NO AS ticketNo, "
                        + "r.VALID_MONTHS AS validMonths, r.ACTIVATION_MODE AS activationMode, "
                        + "r.ACTIVATE_AT AS activateAt, r.STATUS AS status, "
                        + "r.APPR_INSTANCE_ID AS apprInstanceId, r.OUT_WINDOW AS outWindow, "
                        + "r.GRANT_ID AS grantId, "
                        + "r.CREATE_DATE AS createDate "
                        + "FROM erp_admin_perm_request r "
                        + "LEFT JOIN erp_admin_user u ON u.ID = r.TARGET_USER_ID "
                        + "WHERE r.DEL_FLAG = '0' ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append("AND r.STATUS = ? ");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            sql.append("AND (u.USERNAME LIKE ? OR r.ROLES_JSON LIKE ? OR r.REASON LIKE ?) ");
            String kw = "%" + keyword + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        // 16.4.2 升级审批视图：outWindow=true 仅窗口外变更单（add-change-window-console）
        if ("true".equalsIgnoreCase(outWindow)) {
            sql.append("AND r.OUT_WINDOW = '1' ");
        } else if ("false".equalsIgnoreCase(outWindow)) {
            sql.append("AND r.OUT_WINDOW = '0' ");
        }
        sql.append("ORDER BY r.CREATE_DATE DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public Map<String, Object> detail(String id) {
        PermRequest req = requestDao.selectById(id);
        if (req == null) {
            throw new ServiceException(404, "申请单不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", req);
        out.put("targetUsername", username(req.getTargetUserId()));
        try {
            out.put("sodSnapshot", req.getSodSnapshotJson() == null ? Map.of()
                    : MAPPER.readValue(req.getSodSnapshotJson(), Map.class));
        } catch (Exception e) {
            out.put("sodSnapshot", Map.of());
        }
        out.put("approvalLogs", approvalEngine.logs(BIZ_PERM_REQUEST, id));
        return out;
    }

    @Override
    public List<Map<String, Object>> outWindowLedger() {
        // 16.4.3 双人复核台账（spec：会签即复核，三方签署明细为证据面）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.ID AS id, r.KIND AS kind, r.TARGET_USER_ID AS targetUserId, "
                        + "u.USERNAME AS targetUsername, r.RISK_LEVEL AS riskLevel, "
                        + "r.ROLES_JSON AS rolesJson, r.REASON AS reason, r.STATUS AS status, "
                        + "r.CREATE_DATE AS submitTime "
                        + "FROM erp_admin_perm_request r "
                        + "LEFT JOIN erp_admin_user u ON u.ID = r.TARGET_USER_ID "
                        + "WHERE r.DEL_FLAG = '0' AND r.OUT_WINDOW = '1' "
                        + "ORDER BY r.CREATE_DATE DESC");
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> signs = jdbc.queryForList(
                    "SELECT t.ROLE_REQUIRED AS roleCode, t.NODE_NAME AS nodeName, "
                            + "t.SIGNER AS signerId, t.SIGNER_NAME AS signerName, "
                            + "t.STATUS AS signStatus, t.OPINION AS opinion, t.OP_TIME AS opTime "
                            + "FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                            + "WHERE i.BIZ_TYPE = ? AND i.BIZ_ID = ? AND t.DEL_FLAG = '0' "
                            + "AND t.ROLE_REQUIRED IN ('ROLE_CCB_OPS','ROLE_CCB_ARCH','ROLE_CCB_BIZ') "
                            + "ORDER BY t.ROLE_REQUIRED",
                    BIZ_PERM_REQUEST, row.get("id"));
            row.put("ccbSigns", signs);
        }
        return rows;
    }

    // ---------- 校验与工具 ----------

    private void requireNoPending(String targetUserId) {
        Long pend = requestDao.selectCount(new LambdaQueryWrapper<PermRequest>()
                .eq(PermRequest::getTargetUserId, targetUserId)
                .eq(PermRequest::getStatus, PermRequest.ST_PENDING));
        if (pend != null && pend > 0) {
            throw new ServiceException(422, "该用户已有待审批的权限申请，勿重复提交");
        }
    }

    private List<String> requireRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new ServiceException(422, "目标角色必填");
        }
        List<String> out = new ArrayList<>();
        for (String c : roleCodes) {
            if (StringUtils.hasText(c) && !out.contains(c.trim())) {
                out.add(c.trim());
            }
        }
        if (out.isEmpty()) {
            throw new ServiceException(422, "目标角色必填");
        }
        return out;
    }

    private void requireText(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new ServiceException(422, msg);
        }
    }

    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.trim().length() < 10) {
            throw new ServiceException(422, "业务理由必填且不少于 10 字");
        }
    }

    private String riskOf(List<String> roles) {
        if (roles.stream().anyMatch(PrivilegedRoles::contains)) {
            return PermRequest.RISK_PRIVILEGED;
        }
        if (roles.stream().anyMatch(SensitiveRoles::contains)) {
            return PermRequest.RISK_SENSITIVE;
        }
        return PermRequest.RISK_NORMAL;
    }

    private String username(String userId) {
        List<String> names = jdbc.queryForList(
                "SELECT USERNAME FROM erp_admin_user WHERE ID = ?", String.class, userId);
        return names.isEmpty() ? userId : names.get(0);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

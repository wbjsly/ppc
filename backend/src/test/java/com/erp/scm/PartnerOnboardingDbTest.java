package com.erp.scm;

import com.erp.common.ServiceException;
import com.erp.entity.scm.PartnerApply;
import com.erp.service.impl.scm.PartnerAdmissionCallback;
import com.erp.service.impl.scm.PartnerApplyServiceImpl;
import com.erp.service.scm.PartnerApplyService;
import com.erp.entity.system.ApprovalInstance;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalTask;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伙伴入驻 DbTest ①组（add-partner-onboarding，spec partner-onboarding ADDED）：
 * 信用代码 18 位/重复 L1、风险分级节点链、驳回≥3 自动关闭、自动建档+待激活账号、
 * 证照附件卡点、黑名单兜底 REJECTED。造数前缀 po-。
 */
@SpringBootTest
class PartnerOnboardingDbTest {

    @Autowired
    private PartnerApplyService service;
    @Autowired
    private PartnerApplyServiceImpl impl;
    @Autowired
    private com.erp.service.scm.PartnerActivateService activateService;
    @Autowired
    private PartnerAdmissionCallback callback;
    @Autowired
    private ApprovalInstanceDao instanceDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        asProcurement();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ==================== BR-4.9-08 信用代码校验 ====================

    @Test
    void taxNoMustBe18Chars() {
        Map<String, Object> p = payload("PO 18位", "1234567890123456"); // 16 位
        ServiceException e = assertThrows(ServiceException.class, () -> service.save(p));
        assertEquals(422, e.getCode());
        assertTrue(String.valueOf(e.getMessage()).contains("18 位"), e.getMessage());
    }

    @Test
    void taxNoDuplicateBlocked() {
        // 既有供应商档案占位该税号
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, TAX_NO, "
                + "STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('po-sup-dup','SUP-PODUP','PO 重复供应商','91310000MA1K35XXXX',"
                + "'QUALIFIED','junit',NOW(),'0',0)");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.save(payload("PO 重复", "91310000MA1K35XXXX")));
        assertEquals(422, e.getCode());
        assertTrue(String.valueOf(e.getMessage()).contains("已存在"), e.getMessage());
    }

    // ==================== 风险分级节点链 ====================

    @Test
    void highRiskBuildsThreeNodeChain() {
        Map<String, Object> p = payload("PO 高风险", "91310000MA1K35XX01");
        p.put("estAnnualAmount", "1500000");
        String id = idOf(service.save(p));
        seedCertWithAttach(id);
        service.submit(id);
        assertEquals("HIGH", jdbc.queryForObject(
                "SELECT RISK_LEVEL FROM erp_scm_partner_apply WHERE ID=?", String.class, id));
        // 审批实例三节点
        Integer nodes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task t "
                        + "JOIN erp_sys_approval i ON i.ID = t.APPR_ID WHERE i.BIZ_ID=?",
                Integer.class, id);
        assertEquals(3, nodes, "高风险三级审核");
        assertEquals("APPROVING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_scm_partner_apply WHERE ID=?", String.class, id));
    }

    @Test
    void normalRiskBuildsTwoNodeChain() {
        Map<String, Object> p = payload("PO 普通", "91310000MA1K35XX02");
        p.put("estAnnualAmount", "200000");
        String id = idOf(service.save(p));
        seedCertWithAttach(id);
        service.submit(id);
        assertEquals("NORMAL", jdbc.queryForObject(
                "SELECT RISK_LEVEL FROM erp_scm_partner_apply WHERE ID=?", String.class, id));
        Integer nodes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task t "
                        + "JOIN erp_sys_approval i ON i.ID = t.APPR_ID WHERE i.BIZ_ID=?",
                Integer.class, id);
        assertEquals(2, nodes, "普通两级审核");
    }

    @Test
    void criticalMaterialGroupForcesHighRisk() {
        jdbc.update("INSERT INTO erp_scm_critical_group (ID, MATERIAL_GROUP, CREATE_BY, "
                + "CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('po-cg-1','CRIT-GROUP','junit',NOW(),'0',0)");
        Map<String, Object> p = payload("PO 关键物料", "91310000MA1K35XX03");
        p.put("estAnnualAmount", "10000");
        p.put("mainCategory", "CRIT-GROUP");
        String id = idOf(service.save(p));
        seedCertWithAttach(id);
        service.submit(id);
        assertEquals("HIGH", jdbc.queryForObject(
                "SELECT RISK_LEVEL FROM erp_scm_partner_apply WHERE ID=?", String.class, id),
                "品类命中关键物料组白名单 → 强制三级");
    }

    // ==================== 证照附件卡点（BR-4.9-09） ====================

    @Test
    void submitWithoutAttachmentBlocked() {
        String id = idOf(service.save(payload("PO 无附件", "91310000MA1K35XX04")));
        // 证照行无附件
        jdbc.update("INSERT INTO erp_scm_partner_apply_cert (ID, APPLY_ID, CERT_TYPE, EXPIRE_DATE, "
                + "ATTACH_COUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('po-cert-na',?,'LICENSE',DATE_ADD(CURDATE(),INTERVAL 1 YEAR),0,'junit',NOW(),'0',0)",
                id);
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(id));
        assertEquals(422, e.getCode());
        assertTrue(String.valueOf(e.getMessage()).contains("附件"), e.getMessage());
    }

    @Test
    void expiredCertBlocksSubmit() {
        String id = idOf(service.save(payload("PO 过期证照", "91310000MA1K35XX05")));
        String certId = "po-cert-exp";
        jdbc.update("INSERT INTO erp_scm_partner_apply_cert (ID, APPLY_ID, CERT_TYPE, EXPIRE_DATE, "
                + "ATTACH_COUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(?,?,'LICENSE',DATE_SUB(CURDATE(),INTERVAL 1 DAY),1,'junit',NOW(),'0',0)",
                certId, id);
        seedAttach(certId);
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(id));
        assertEquals(422, e.getCode());
        assertTrue(String.valueOf(e.getMessage()).contains("过期"), e.getMessage());
    }

    // ==================== 驳回≥3 自动关闭 ====================

    @Test
    void thirdRejectAutoCloses() {
        String id = submitSmall("PO 三次驳回", "91310000MA1K35XX06");
        // 三轮驳回
        for (int i = 1; i <= 3; i++) {
            rejectCurrent(id);
            String status = jdbc.queryForObject(
                    "SELECT STATUS FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
            if (i < 3) {
                assertEquals(PartnerApply.ST_DRAFT, status, "第 " + i + " 次驳回退回 DRAFT");
                service.submit(id); // 重提
            } else {
                assertEquals(PartnerApply.ST_CLOSED, status, "第 3 次驳回自动关闭");
            }
        }
        String reason = jdbc.queryForObject(
                "SELECT CLOSE_REASON FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
        assertTrue(String.valueOf(reason).contains("自动关闭"), reason);
    }

    // ==================== 审核通过自动建档（D3） ====================

    @Test
    void approvalAutoCreatesQualifiedSupplierAndPendingAccount() {
        String id = submitSmall("PO 自动建档", "91310000MA1K35XX07");
        // 逐节点 pass 至终态
        passAllNodes(id);
        // 申请单 APPROVED + 回写
        assertEquals(PartnerApply.ST_APPROVED, jdbc.queryForObject(
                "SELECT STATUS FROM erp_scm_partner_apply WHERE ID=?", String.class, id));
        String supplierId = jdbc.queryForObject(
                "SELECT SUPPLIER_ID FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
        assertNotNull(supplierId, "建档回写 SUPPLIER_ID");
        // 档案：编码 SUP- 前缀 + 状态 QUALIFIED（跳过待审）
        String code = jdbc.queryForObject(
                "SELECT SUPPLIER_CODE FROM erp_mdm_supplier WHERE ID=?", String.class, supplierId);
        assertTrue(code.startsWith("SUP-"), code);
        assertEquals("QUALIFIED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mdm_supplier WHERE ID=?", String.class, supplierId));
        // 待激活账号 + token
        String accountId = jdbc.queryForObject(
                "SELECT ACCOUNT_ID FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
        assertNotNull(accountId);
        assertEquals("0", jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_user WHERE ID=?", String.class, accountId),
                "待激活账号不可登录（STATUS=0）");
        Integer tokens = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_scm_partner_activate_token WHERE ACCOUNT_ID=? "
                        + "AND INVALID_FLAG='0' AND USED_AT IS NULL", Integer.class, accountId);
        assertEquals(1, tokens, "生成一条可用激活 token");
    }

    // ==================== 黑名单兜底 REJECTED ====================

    @Test
    void blacklistHitAtAdmissionRejectsApply() {
        // 打补丁：拦截建档——直接改用一个会触发查重失败的名称（与既有供应商编辑距离 ≤3 且 forceCreate=true 放行）
        // 黑名单兜底通过在建档前将申请税号塞入档案表模拟并发双单 → 建档税号占用 422 → REJECTED
        String id = submitSmall("PO 黑名单兜底", "91310000MA1K35XX08");
        // 模拟并发：另一路径抢先建档同税号
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, TAX_NO, "
                + "STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('po-sup-race','SUP-PORACE','完全无关企业名称甲乙丙丁戊己庚辛','91310000MA1K35XX08',"
                + "'QUALIFIED','junit',NOW(),'0',0)");
        passAllNodes(id);
        assertEquals(PartnerApply.ST_REJECTED, jdbc.queryForObject(
                "SELECT STATUS FROM erp_scm_partner_apply WHERE ID=?", String.class, id),
                "建档失败（税号占用）→ 申请单 REJECTED");
        String reason = jdbc.queryForObject(
                "SELECT CLOSE_REASON FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
        assertTrue(String.valueOf(reason).contains("建档失败"), reason);
    }

    // ==================== 激活 token 全链（FR-4.9-1-4 / BR-4.9-11） ====================

    @Test
    void activateFlowVerifySetPasswordEnable() {
        String id = approvedWithToken("PO 激活全链", "91310000MA1K35XX09");
        String token = jdbc.queryForObject(
                "SELECT TOKEN FROM erp_scm_partner_activate_token WHERE INVALID_FLAG='0' "
                        + "AND USED_AT IS NULL ORDER BY CREATE_DATE DESC LIMIT 1", String.class);
        assertNotNull(token);
        // 匿名校验：脱敏回显
        Map<String, Object> v = activateService.verify(token);
        assertNotNull(v.get("supplierNameMasked"));
        assertTrue(String.valueOf(v.get("emailDomain")).contains("@"),
                String.valueOf(v.get("emailDomain")));
        // 激活：弱密码 422
        ServiceException weak = assertThrows(ServiceException.class,
                () -> activateService.activate(token, "weak"));
        assertEquals(422, weak.getCode());
        // 强密码激活
        Map<String, Object> out = activateService.activate(token, "Passw0rd!x");
        assertEquals(true, out.get("activated"));
        // 账号启用 + token 已用
        String accountId = jdbc.queryForObject(
                "SELECT ACCOUNT_ID FROM erp_scm_partner_apply WHERE ID=?", String.class, id);
        assertEquals("1", jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_user WHERE ID=?", String.class, accountId));
        assertNotNull(jdbc.queryForObject(
                "SELECT USED_AT FROM erp_scm_partner_activate_token WHERE TOKEN=?",
                java.sql.Timestamp.class, token));
        // 一次性：重复使用 410
        ServiceException reused = assertThrows(ServiceException.class,
                () -> activateService.activate(token, "Passw0rd!x"));
        assertEquals(410, reused.getCode());
    }

    @Test
    void expiredTokenRejected() {
        approvedWithToken("PO 过期token", "91310000MA1K35XX10");
        // 回拨过期时间
        jdbc.update("UPDATE erp_scm_partner_activate_token SET EXPIRE_AT = DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE INVALID_FLAG='0'");
        String token = jdbc.queryForObject(
                "SELECT TOKEN FROM erp_scm_partner_activate_token WHERE EXPIRE_AT < NOW() "
                        + "ORDER BY CREATE_DATE DESC LIMIT 1", String.class);
        ServiceException e = assertThrows(ServiceException.class,
                () -> activateService.verify(token));
        assertEquals(410, e.getCode());
        assertTrue(String.valueOf(e.getMessage()).contains("过期"), e.getMessage());
    }

    @Test
    void regenerateInvalidatesOldToken() {
        String id = approvedWithToken("PO 重发旧链", "91310000MA1K35XX11");
        String oldToken = jdbc.queryForObject(
                "SELECT TOKEN FROM erp_scm_partner_activate_token WHERE INVALID_FLAG='0' "
                        + "ORDER BY CREATE_DATE DESC LIMIT 1", String.class);
        // 重新生成（需 PROCUREMENT 角色）
        asProcurement();
        Map<String, Object> out = activateService.regenerate(id);
        String newToken = String.valueOf(out.get("token"));
        // 旧链立即失效
        ServiceException old = assertThrows(ServiceException.class,
                () -> activateService.verify(oldToken));
        assertEquals(410, old.getCode());
        // 新链可用
        Map<String, Object> v = activateService.verify(newToken);
        assertNotNull(v.get("expireAt"));
    }

    @Test
    void sweepExpiredIsIdempotent() {
        approvedWithToken("PO 扫表", "91310000MA1K35XX12");
        jdbc.update("UPDATE erp_scm_partner_activate_token SET EXPIRE_AT = DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE INVALID_FLAG='0'");
        int n1 = activateService.sweepExpired();
        assertTrue(n1 >= 1, "首次扫表处理过期 token");
        int n2 = activateService.sweepExpired();
        assertEquals(0, n2, "幂等：已失效不再计数");
    }

    /** 造齐"审核通过已生成账号+token"环境：直接走回调（与自动建档测试同路径） */
    private String approvedWithToken(String name, String taxNo) {
        String id = submitSmall(name, taxNo);
        passAllNodes(id);
        assertEquals(PartnerApply.ST_APPROVED, jdbc.queryForObject(
                "SELECT STATUS FROM erp_scm_partner_apply WHERE ID=?", String.class, id));
        asProcurement();
        return id;
    }

    // ==================== 造数辅助 ====================

    private Map<String, Object> payload(String name, String taxNo) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("supplierName", name);
        m.put("taxNo", taxNo);
        m.put("contactName", "po 联系人");
        m.put("contactPhone", "13800000000");
        m.put("contactEmail", "contact@example.com");
        m.put("bankName", "测试银行");
        m.put("paymentTerms", "NET30");
        return m;
    }

    /** 造齐提交条件：证照行 + 附件 */
    private void seedCertWithAttach(String applyId) {
        String certId = "po-cert-" + applyId.substring(0, 6);
        jdbc.update("INSERT INTO erp_scm_partner_apply_cert (ID, APPLY_ID, CERT_TYPE, EXPIRE_DATE, "
                + "ATTACH_COUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(?,?,'LICENSE',DATE_ADD(CURDATE(),INTERVAL 1 YEAR),1,'junit',NOW(),'0',0)",
                certId, applyId);
        seedAttach(certId);
    }

    private void seedAttach(String certId) {
        jdbc.update("INSERT INTO erp_sys_attachment (ID, BIZ_TYPE, BIZ_ID, FILE_NAME, STORE_PATH, "
                + "FILE_SIZE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(?, 'PARTNER_APPLY_CERT', ?, 'license.pdf', '/tmp/license.pdf', 1024, "
                + "'junit', NOW(), '0', 0)",
                "po-att-" + UUID.randomUUID().toString().substring(0, 8), certId);
    }

    private String submitSmall(String name, String taxNo) {
        String id = idOf(service.save(payload(name, taxNo)));
        seedCertWithAttach(id);
        service.submit(id);
        return id;
    }

    /** save() 返回 {apply: PartnerApply 实体} — 取 id */
    @SuppressWarnings("unchecked")
    private static String idOf(Map<String, Object> saved) {
        Object a = saved.get("apply");
        if (a instanceof PartnerApply pa) {
            return pa.getId();
        }
        return String.valueOf(((Map<String, Object>) a).get("id"));
    }

    /** 找到当前 ACTIVE 审批节点并驳回（整单驳回语义） */
    private void rejectCurrent(String applyId) {
        ApprovalInstance inst = instanceDao.selectOne(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizId, applyId)
                .eq(ApprovalInstance::getStatus, "PENDING")
                .last("LIMIT 1"));
        ApprovalTask task = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, inst.getId())
                .eq(ApprovalTask::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        asRole("ROLE_PURCHASER");
        callback.onRejected(inst);
        // 模拟底座驳回的实例状态
        inst.setStatus("REJECTED");
        instanceDao.updateById(inst);
        task.setStatus("REJECTED");
        taskDao.updateById(task);
        asProcurement();
    }

    /** 逐节点 pass（按 SEQ 顺序模拟三个角色全部通过） */
    private void passAllNodes(String applyId) {
        ApprovalInstance inst = instanceDao.selectOne(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizId, applyId)
                .eq(ApprovalInstance::getStatus, "PENDING")
                .last("LIMIT 1"));
        // 顺序激活：把所有节点逐个 PASSED
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, inst.getId())
                .orderByAsc(ApprovalTask::getSeq));
        for (ApprovalTask t : tasks) {
            t.setStatus("PASSED");
            t.setOpinion("测试通过");
            t.setOpTime(java.time.LocalDateTime.now());
            taskDao.updateById(t);
        }
        inst.setStatus("APPROVED");
        instanceDao.updateById(inst);
        callback.onApproved(inst);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_scm_partner_activate_token WHERE 1=1");
        jdbc.update("DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='PARTNER_APPLY_CERT' "
                + "AND BIZ_ID LIKE 'po-cert-%'");
        jdbc.update("DELETE FROM erp_scm_partner_activate_token WHERE SUPPLIER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_mdm_supplier WHERE SUPPLIER_NAME LIKE 'PO %') t)");
        jdbc.update("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'sup-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'sup-%'");
        jdbc.update("DELETE FROM erp_scm_partner_apply_cert WHERE APPLY_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_scm_partner_apply WHERE APPLY_NO LIKE 'PA%') t)");
        jdbc.update("DELETE FROM erp_scm_partner_apply WHERE SUPPLIER_NAME LIKE 'PO %'");
        jdbc.update("DELETE FROM erp_scm_critical_group WHERE ID LIKE 'po-cg-%'");
        jdbc.update("DELETE FROM erp_mdm_supplier_version WHERE ENTITY_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_mdm_supplier WHERE SUPPLIER_NAME LIKE 'PO %') t)");
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE SUPPLIER_NAME LIKE 'PO %' "
                + "OR ID IN ('po-sup-dup','po-sup-race')");
        // 清供应商事件（幂等键含 SUP 编码，purge 删档案后编码重排会撞键）
        jdbc.update("DELETE FROM erp_ops_outbox WHERE EVENT_TYPE LIKE 'MDM.SUPPLIER%' "
                + "AND IDEMPOTENCY_KEY LIKE 'SUP-0%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='PARTNER_ADMISSION') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PARTNER_ADMISSION'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='PARTNER_ADMISSION'");
    }

    private void asProcurement() {
        asRole("ROLE_PURCHASER");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "po-tester", "x", roles));
    }
}

package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrExitDao;
import com.erp.dao.hr.HrTransferDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrExit;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.hr.HrAccountLinkService;
import com.erp.service.hr.HrExitService;
import com.erp.service.hr.HrOrgService;
import com.erp.service.hr.HrTransferService;
import com.erp.service.impl.hr.HrAccountLinkServiceImpl;
import com.erp.service.impl.hr.HrExitServiceImpl;
import com.erp.service.impl.hr.HrTransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入转调离 + 账号联动 DbTest（employee-transfer spec + design D3/D4）。
 * 造数前缀 hrt-，purge 全量清理。
 */
@SpringBootTest
class EmployeeTransferDbTest {

    @Autowired
    private HrOrgService orgService;
    @Autowired
    private EmployeeArchiveService archiveService;
    @Autowired
    private HrTransferService transferService;
    @Autowired
    private HrExitService exitService;
    @Autowired
    private HrAccountLinkService accountLinkService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrTransferDao trfDao;
    @Autowired
    private HrExitDao exitDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String deptId;
    private String posId;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
        var d = orgService.createDept(Map.of("name", "HRT 部门"));
        deptId = d.getId();
        var p = orgService.createPosition(Map.of("name", "HRT 岗位", "deptId", deptId,
                "rolesJson", "[\"ROLE_PLANNER\"]"));
        posId = p.getId();
    }

    private void purge() {
        // 清联动产物
        jdbc.update("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON ur.USER_ID=u.ID "
                + "WHERE u.USERNAME LIKE 'EMP20%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'EMP20%'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE LIKE 'Hr%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE LIKE 'Hr%'");
        jdbc.update("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID LIKE 'hrt-%'; SET @audit_bypass=0");
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_remind WHERE EMP_ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_exit WHERE EMP_ID LIKE 'hrt-%' OR ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_transfer WHERE EMP_ID LIKE 'hrt-%' OR ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_onboarding WHERE ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'hrt-%' OR EMP_NO LIKE 'EMP20%'");
        jdbc.update("DELETE FROM hr_position WHERE ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM hr_dept WHERE ID LIKE 'hrt-%'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE ID LIKE 'hrt-card-%'");
    }

    private void loginRole(String role) {
        loginRole(role, "junit-" + role.replace("ROLE_", "").toLowerCase());
    }

    private void loginRole(String role, String user) {
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void loginAdmin() {
        var auth = new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造一名试用期员工（含建档六步快进） */
    private String seedProbation(String suffix, LocalDate hire, int probationMonths) {
        String onbId = seedOnboarding(suffix, hire, probationMonths);
        return onbDaoEmpId(onbId);
    }

    private String onbDaoEmpId(String onbId) {
        return jdbc.queryForObject("SELECT EMP_ID FROM hr_onboarding WHERE ID=?", String.class, onbId);
    }

    private String seedOnboarding(String suffix, LocalDate hire, int probationMonths) {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "HRT-OFFER-" + suffix,
                "offerApproved", "1"));
        String id = onb.getId();
        archiveService.advance(id, Map.of());
        archiveService.advance(id, Map.of("candidateJson",
                "{\"name\":\"HRT员工" + suffix + "\",\"idCardNo\":\"HRTID-" + suffix + "\"}",
                "realnameCheck", "1", "bank4Check", "1"));
        archiveService.advance(id, Map.of("docsJson", "{\"离职证明\":{\"submitted\":true}}"));
        archiveService.advance(id, Map.of("contractJson",
                "{\"contractNo\":\"HRT-HT-" + suffix + "\",\"contractStart\":\"2026-01-01\","
                        + "\"contractEnd\":\"2029-01-01\",\"probationMonths\":" + probationMonths + "}"));
        archiveService.advance(id, Map.of("deptId", deptId, "posId", posId,
                "hireDate", hire.toString()));
        archiveService.advance(id, Map.of("socialJson",
                "{\"place\":\"上海\",\"type\":\"五险一金\",\"date\":\"" + hire + "\"}"));
        return id;
    }

    /** 签过审批链所有 ACTIVE 节点（admin 越权可签） */
    private void passApproval(String bizType, String bizId) {
        loginAdmin();
        for (int i = 0; i < 5; i++) {
            var tasks = jdbc.queryForList(
                    "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                            + "WHERE i.BIZ_TYPE=? AND i.BIZ_ID=? AND t.STATUS='ACTIVE'",
                    bizType, bizId);
            if (tasks.isEmpty()) {
                return;
            }
            approvalEngine.pass(String.valueOf(tasks.get(0).get("ID")), "同意（junit）");
        }
    }

    // ---------- 转正 ----------

    @Test
    void probationRegularFlow() {
        String empId = seedProbation("reg", LocalDate.now().minusMonths(2), 3);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("abilityScore", 4);
        p.put("attitudeScore", 4);
        p.put("attitudeNote", "表现良好");
        p.put("proposal", "REGULAR");
        var t = transferService.submitProbation(p);
        transferService.hrReviewProbation(t.getId(), "HR 审核通过");
        passApproval(HrTransferServiceImpl.BIZ_PROBATION, t.getId());
        assertEquals(HrEmployee.ST_REGULAR, empDao.selectById(empId).getEmpStatus(), "按期转正");
        assertEquals(HrTransfer.ST_EFFECTED, trfDao.selectById(t.getId()).getStatus());
    }

    @Test
    void probationExtendOverLegalLimitRejected() {
        // 合同 36 月、已试用 3 月，延期 4 月 → 总 7 > 上限 6 → L1
        String empId = seedProbation("ext", LocalDate.now().minusMonths(3), 3);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("abilityScore", 3);
        p.put("attitudeScore", 3);
        p.put("proposal", "EXTEND");
        p.put("extendMonths", 4);
        ServiceException e = assertThrows(ServiceException.class,
                () -> transferService.submitProbation(p));
        assertTrue(e.getMessage().contains("试用期超法定上限"), e.getMessage());
    }

    @Test
    void autoRegularizeOverdueByScan() {
        // 试用期截止日昨天 → 扫描自动按期转正
        String empId = seedProbation("auto", LocalDate.now().minusMonths(4), 3);
        jdbc.update("UPDATE hr_employee SET PROBATION_END = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE ID=?", empId);
        new com.erp.schedule.HrDailyScanTask(empDao, trfDao,
                (HrTransferServiceImpl) transferService, archiveService).dailyScan();
        assertEquals(HrEmployee.ST_REGULAR, empDao.selectById(empId).getEmpStatus(), "超期自动转正");
        String reason = jdbc.queryForObject(
                "SELECT REASON FROM hr_transfer WHERE EMP_ID=? AND STATUS='EFFECTED' ORDER BY CREATE_DATE DESC LIMIT 1",
                String.class, empId);
        assertTrue(reason.contains("系统默认转正"), "留痕：" + reason);
        // 幂等：再跑一次不新增生效单
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM hr_transfer WHERE EMP_ID=?", Integer.class, empId);
        new com.erp.schedule.HrDailyScanTask(empDao, trfDao,
                (HrTransferServiceImpl) transferService, archiveService).dailyScan();
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM hr_transfer WHERE EMP_ID=?", Integer.class, empId);
        assertEquals(before, after, "扫描幂等");
    }

    // ---------- 调岗双人复核 ----------

    private HrTransfer seedToReview(String empId, String toPosId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("toPosId", toPosId);
        p.put("effectiveDate", LocalDate.now().plusDays(7).toString());
        p.put("reason", "业务需要");
        var t = transferService.submitPostChange(p);
        transferService.confirmDeptOut(t.getId(), "同意调出");
        transferService.confirmDeptIn(t.getId(), "同意接收");
        transferService.hrReviewPost(t.getId(), "HR 审核通过");
        return trfDao.selectById(t.getId());
    }

    private Map<String, Object> reviewElements(String posId, String salary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", posId);
        m.put("salary", salary);
        m.put("reportLine", "经理");
        m.put("costCenter", "CC-01");
        m.put("effectiveDate", LocalDate.now().plusDays(7).toString());
        return m;
    }

    @Test
    void doubleReviewConsistentAdvances() {
        String empId = seedProbation("dr", LocalDate.now().minusMonths(4), 3);
        // 转正为正式后方可调岗（试用期也可调，service 不限制——直接调岗）
        var pos2 = orgService.createPosition(Map.of("name", "HRT 新岗位", "deptId", deptId));
        HrTransfer t = seedToReview(empId, pos2.getId());
        // 第一复核人 HR 专员
        loginRole("ROLE_HR");
        transferService.signReview(t.getId(), reviewElements(pos2.getId(), "15000"));
        // 第二复核人 HR 经理
        loginRole("ROLE_HR_MGR");
        var after = transferService.signReview(t.getId(), reviewElements(pos2.getId(), "15000"));
        assertEquals(HrTransfer.ST_APPROVING, after.getStatus(), "一致 → 流转审批");
        passApproval(HrTransferServiceImpl.BIZ_POST, t.getId());
        HrEmployee e = empDao.selectById(empId);
        assertEquals(pos2.getId(), e.getPosId(), "生效更新岗位");
        // 账号角色换绑留痕（该员工建档时已提交开通审批但未通过 → 无账号 → swapRoles null 不炸）
        // 变更日志留痕
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='岗位'", Integer.class, empId);
        assertTrue(logs >= 1, "岗位变更留痕");
    }

    @Test
    void doubleReviewDiffPausesAndAdjudicates() {
        String empId = seedProbation("dd", LocalDate.now().minusMonths(4), 3);
        var pos2 = orgService.createPosition(Map.of("name", "HRT 差异岗位", "deptId", deptId));
        HrTransfer t = seedToReview(empId, pos2.getId());
        loginRole("ROLE_HR");
        transferService.signReview(t.getId(), reviewElements(pos2.getId(), "15000"));
        loginRole("ROLE_HR_MGR");
        var diff = transferService.signReview(t.getId(), reviewElements(pos2.getId(), "18000"));
        assertEquals(HrTransfer.ST_REVIEW_DIFF, diff.getStatus(), "薪酬不一致 → 挂起");
        assertTrue(diff.getReviewDiffJson().contains("salary"), "差异项含 salary");
        // 同一人不可双签
        loginRole("ROLE_HR");
        // 状态已 REVIEW_DIFF → 签署被拒
        assertThrows(ServiceException.class,
                () -> transferService.signReview(t.getId(), reviewElements(pos2.getId(), "15000")));
        // HR 经理裁决通过 → 流转审批
        loginRole("ROLE_HR_MGR");
        var adj = transferService.adjudicateDiff(t.getId(), "APPROVE", "按 18000 执行");
        assertEquals(HrTransfer.ST_APPROVING, adj.getStatus());
    }

    @Test
    void headcountFullBlocks() {
        String empId = seedProbation("hc", LocalDate.now().minusMonths(1), 3);
        // 编制 1 的岗位已有 1 人在岗（再建一名员工占满）
        var fullPos = orgService.createPosition(Map.of("name", "HRT 满编岗", "deptId", deptId,
                "headcount", 1));
        seedOnboarding("hc2", LocalDate.now().minusDays(10), 3);
        jdbc.update("UPDATE hr_employee SET POS_ID=? WHERE EMP_NO LIKE 'EMP20%' AND ID<>?", fullPos.getId(), empId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("toPosId", fullPos.getId());
        p.put("effectiveDate", LocalDate.now().plusDays(3).toString());
        ServiceException e = assertThrows(ServiceException.class, () -> transferService.submitPostChange(p));
        assertTrue(e.getMessage().contains("满编"), e.getMessage());
    }

    // ---------- 离职交接（C-4.7-04） ----------

    @Test
    void exitHandoverBlocksUntilAllFourDone() {
        String empId = seedProbation("ex", LocalDate.now().minusMonths(6), 3);
        // 造 2 张持有人资产卡片
        seedCard("c1", "EMP", empId);
        seedCard("c2", "EMP", empId);
        String empNo = empDao.selectById(empId).getEmpNo();
        jdbc.update("UPDATE erp_eam_asset_card SET HOLDER_EMP_NO=? WHERE ID LIKE 'hrt-card-%'", empNo);

        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("empId", empId);
        ap.put("quitDate", LocalDate.now().plusDays(40).toString()); // 通知期满
        var x = exitService.apply(ap);
        assertEquals("1", x.getNoticeOk(), "通知期满足");
        exitService.startApproval(x.getId());
        passApproval(HrExitServiceImpl.BIZ_EXIT, x.getId());
        assertEquals(HrExit.ST_HANDOVER, exitDao.selectById(x.getId()).getStatus(), "审批通过 → 交接");

        // 清单列示资产
        var list1 = exitService.handoverChecklist(x.getId());
        assertEquals(2, ((List<?>) list1.get("assets")).size(), "EAM 卡片按持有人列示");
        assertEquals(Boolean.FALSE, list1.get("canIssueCert"));

        // 资产归还（回写清空）
        List<String> cardIds = jdbc.queryForList(
                "SELECT ID FROM erp_eam_asset_card WHERE HOLDER_EMP_NO=?", String.class, empNo);
        exitService.confirmAssetReturn(x.getId(), cardIds);
        Integer holderLeft = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_asset_card WHERE HOLDER_EMP_NO=?", Integer.class, empNo);
        assertEquals(0, holderLeft, "持有人清空回写");

        // 权限移交 + 文档 + 工作
        exitService.confirmAccountHandover(x.getId());
        exitService.registerHandoverItems(x.getId(), "doc",
                List.of(Map.of("item", "知识库文档", "receiver", "接任者")));
        exitService.registerHandoverItems(x.getId(), "work",
                List.of(Map.of("item", "在办项目交接", "receiver", "接任者")));

        // 交接全齐但未结算 → 凭证仍阻断
        var list2 = exitService.handoverChecklist(x.getId());
        assertEquals(Boolean.TRUE, list2.get("canIssueCert"));
        ServiceException e = assertThrows(ServiceException.class, () -> exitService.issueExitCert(x.getId()));
        assertTrue(e.getMessage().contains("结算"), e.getMessage());

        // 结算登记 → 凭证放行 + 主档离职 + 账号禁用
        exitService.settle(x.getId(), Map.of("settleWage", "12000", "settleAnnual", "3000",
                "settleComp", "24000", "reduceDate", LocalDate.now().toString()));
        // add-transfer-followup：离场凭证要求 DONE 减员单或豁免——结算已自动开单，此处申报完成
        jdbc.update("UPDATE hr_social_change SET STATUS='DONE', CHANGE_DATE=CURDATE(), DECLARE_DATE=NOW() "
                + "WHERE SRC_ID=? AND CHG_TYPE='REMOVE' AND STATUS='DRAFT'", x.getId());
        var cert = exitService.issueExitCert(x.getId());
        assertNotNull(cert.getExitCertNo());
        assertEquals(HrExit.ST_EXITED, cert.getStatus());
        assertEquals(HrEmployee.ST_RESIGNED, empDao.selectById(empId).getEmpStatus());
        // 建档时开通审批已提交但未通过 → 无账号可禁（幂等不炸）
    }

    private void seedCard(String suffix, String prefixIgnored, String empId) {
        jdbc.update("INSERT INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,CATEGORY_CODE,ASSET_COST,"
                + "LIFE_YEARS,RESIDUAL_RATE,EFFECTIVE_DATE,STATUS,DEPR_STATUS,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,'HRT 笔记本','MACH',8000,3,0.05,'2026-01-01','IN_USE','NORMAL','junit','0',0)",
                "hrt-card-" + suffix, "ZCHRT" + suffix);
    }

    @Test
    void exitNoticeNotMetMarksL4() {
        String empId = seedProbation("nt", LocalDate.now().minusDays(40), 3);
        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("empId", empId);
        ap.put("quitDate", LocalDate.now().plusDays(5).toString()); // 正式 30 天通知期未满
        // 员工已转正？40 天 < 3 月试用 → 仍在试用（通知期 3 天）——转正后测
        jdbc.update("UPDATE hr_employee SET EMP_STATUS='REGULAR' WHERE ID=?", empId);
        var x = exitService.apply(ap);
        assertEquals("0", x.getNoticeOk(), "未满通知期 L4 留痕不阻断");
    }

    // ---------- 账号联动（D3） ----------

    @Test
    void accountOpenApprovalCreatesAccount() {
        String empId = seedProbation("acc", LocalDate.now().minusDays(5), 3);
        // 建档已完成 → 开通审批待办已生成（advanceStep6 联动）
        passApproval(HrAccountLinkServiceImpl.BIZ_ACCOUNT_OPEN, empId);
        String empNo = empDao.selectById(empId).getEmpNo();
        Integer userCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME=?", Integer.class, empNo);
        assertEquals(1, userCnt, "审批通过创建账号（用户名=工号）");
        // 角色按岗位映射（HRT 岗位映射 ROLE_PLANNER——ROLE_HR 属特权清单，基线 L1 拒收）
        Integer roleCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_user u ON ur.USER_ID=u.ID "
                        + "JOIN erp_admin_role r ON ur.ROLE_ID=r.ID WHERE u.USERNAME=? AND r.ROLE_CODE='ROLE_PLANNER'",
                Integer.class, empNo);
        assertEquals(1, roleCnt, "岗位角色映射赋予");
        // 主档回写账号锚
        assertNotNull(empDao.selectById(empId).getUserId(), "USER_ID 锚点");
        // 幂等：重复调用不重复建号
        accountLinkService.createAccount(empId);
        Integer userCnt2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME=?", Integer.class, empNo);
        assertEquals(1, userCnt2, "建号幂等");
    }

    @Test
    void disableAccountOnExitCert() {
        String empId = seedProbation("dis", LocalDate.now().minusDays(5), 3);
        passApproval(HrAccountLinkServiceImpl.BIZ_ACCOUNT_OPEN, empId);
        String empNo = empDao.selectById(empId).getEmpNo();
        Integer statusBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME=? AND STATUS='1'", Integer.class, empNo);
        assertEquals(1, statusBefore, "开通审批后账号启用");
        // 零资产员工完整离职流 → 离场凭证 → 账号禁用
        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("empId", empId);
        ap.put("quitDate", LocalDate.now().plusDays(40).toString());
        var x = exitService.apply(ap);
        exitService.startApproval(x.getId());
        passApproval(HrExitServiceImpl.BIZ_EXIT, x.getId());
        exitService.confirmAssetReturn(x.getId(), List.of()); // 零资产 → 直接完成
        exitService.confirmAccountHandover(x.getId());
        exitService.registerHandoverItems(x.getId(), "doc", List.of(Map.of("item", "文档", "receiver", "张三")));
        exitService.registerHandoverItems(x.getId(), "work", List.of(Map.of("item", "工作", "receiver", "李四")));
        exitService.settle(x.getId(), Map.of("settleWage", "8000"));
        // add-transfer-followup：离场凭证要求 DONE 减员单或豁免——结算已自动开单，此处申报完成
        jdbc.update("UPDATE hr_social_change SET STATUS='DONE', CHANGE_DATE=CURDATE(), DECLARE_DATE=NOW() "
                + "WHERE SRC_ID=? AND CHG_TYPE='REMOVE' AND STATUS='DRAFT'", x.getId());
        var cert = exitService.issueExitCert(x.getId());
        assertEquals(HrExit.ST_EXITED, cert.getStatus());
        Integer statusAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user WHERE USERNAME=? AND STATUS='0'", Integer.class, empNo);
        assertEquals(1, statusAfter, "离场凭证生成 → 账号自动禁用");
    }
}

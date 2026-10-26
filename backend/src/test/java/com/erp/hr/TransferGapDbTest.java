package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrTransferDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrSocialChange;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.hr.HrOrgService;
import com.erp.service.hr.HrSocialChangeService;
import com.erp.service.hr.HrTransferService;
import com.erp.service.impl.hr.HrTransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调岗/转正缺口补齐 DbTest（add-transfer-followup：带宽 L2 节点/缴纳地变更/转正调薪）。
 * 造数前缀 tgap-。
 */
@SpringBootTest
class TransferGapDbTest {

    @Autowired
    private HrTransferService transferService;
    @Autowired
    private HrOrgService orgService;
    @Autowired
    private HrSocialChangeService changeService;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrTransferDao trfDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String deptId;
    private String bandPosId;   // 职级带宽 10000-18000
    private String noBandPosId; // 无职级（带宽跳过）

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
        var d = orgService.createDept(Map.of("name", "TGAP 部门"));
        deptId = d.getId();
        jdbc.update("INSERT INTO hr_grade (ID, GRADE_NO, NAME, SERIES, SAL_MIN, SAL_MAX, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tgap-grade', 'TG-G1', 'TGAP 职级', '管理序列', 10000, 18000, '1', 'junit', '0', 0)");
        var p1 = orgService.createPosition(Map.of("name", "TGAP 岗位A", "deptId", deptId,
                "rolesJson", "[\"ROLE_HR\"]"));
        bandPosId = p1.getId();
        // createPosition 不透传 gradeId → 建后直连挂职级
        jdbc.update("UPDATE hr_position SET GRADE_ID='tgap-grade' WHERE ID=?", bandPosId);
        var p2 = orgService.createPosition(Map.of("name", "TGAP 岗位B", "deptId", deptId,
                "rolesJson", "[\"ROLE_HR\"]"));
        noBandPosId = p2.getId();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 共享库防残留（冒烟 generate 全库拾取）
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE IN ('HrPostChange','HrProbation') AND i.BIZ_ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('HrPostChange','HrProbation') "
                + "AND BIZ_ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_remind WHERE EMP_ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_change_log WHERE EMP_ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_transfer WHERE EMP_ID LIKE 'tgap-%' OR ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_position WHERE ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_dept WHERE ID LIKE 'tgap-%'");
        jdbc.update("DELETE FROM hr_grade WHERE ID='tgap-grade'");
    }

    private void loginRole(String role) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-" + role.toLowerCase(), null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(role));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String seedEmp(String suffix, String posId) {
        String id = "tgap-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, POS_ID, EMP_STATUS, "
                + "BASE_SALARY, SOCIAL_PLACE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'REGULAR', 15000, 'DEMO市', 'junit', '0', 0)",
                id, "TGAP-" + suffix.toUpperCase(), "调岗缺口员工" + suffix, "TGAPID-" + suffix, deptId, posId);
        return id;
    }

    private Map<String, Object> postPayload(String empId, String toPosId, String salary) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("toPosId", toPosId);
        p.put("effectiveDate", LocalDate.now().plusDays(7).toString());
        p.put("salaryAdvice", salary);
        p.put("reason", "junit 调岗");
        return p;
    }

    /** 双人复核两签（HR 经理 + 另一 HR 专员），全一致 → 流转审批 */
    private void signTwice(HrTransfer t, Map<String, Object> elements) {
        loginRole("ROLE_HR_MGR");
        Map<String, Object> p1 = new LinkedHashMap<>(elements);
        p1.put("reviewer", "mgr");
        transferService.signReview(t.getId(), p1);
        loginRole("ROLE_HR");
        Map<String, Object> p2 = new LinkedHashMap<>(elements);
        p2.put("reviewer", "specialist");
        transferService.signReview(t.getId(), p2);
    }

    private int approvalTaskCount(String trfId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID="
                        + "(SELECT APPR_ID FROM hr_transfer WHERE ID=?)", Integer.class, trfId);
        return n == null ? 0 : n;
    }

    // ---------- 带宽 L2 ----------

    @Test
    void outOfBandAppendsL2Node() {
        String empId = seedEmp("b1", noBandPosId);
        var t = transferService.submitPostChange(postPayload(empId, bandPosId, "22000"));
        assertEquals("1", t.getBandExceeded(), "超上限标记");
        assertNotNull(t.getBandNote());
        // 走完双确认 + HR 审核 + 双人复核 → 审批
        loginRole("ROLE_HR");
        transferService.confirmDeptOut(t.getId(), "同意调出");
        transferService.confirmDeptIn(t.getId(), "同意调入");
        transferService.hrReviewPost(t.getId(), "HR 审核通过");
        signTwice(t, Map.of("position", "TGAP 岗位A", "salary", "22000",
                "reportLine", "mgr", "costCenter", "CC-TEST",
                "effectiveDate", t.getEffectiveDate().toString()));
        assertEquals(HrTransfer.ST_APPROVING, trfDao.selectById(t.getId()).getStatus());
        assertEquals(2, approvalTaskCount(t.getId()), "超带宽追加第二个 HR 经理 L2 节点");
    }

    @Test
    void inBandKeepsSingleNode() {
        String empId = seedEmp("b2", noBandPosId);
        var t = transferService.submitPostChange(postPayload(empId, bandPosId, "15000"));
        // 既有语义：1=超上限 / 0=低于下限 / NULL=带内未触发
        assertTrue(t.getBandExceeded() == null || t.getBandExceeded().isEmpty(), "带内不触发标记：" + t.getBandExceeded());
        loginRole("ROLE_HR");
        transferService.confirmDeptOut(t.getId(), "同意");
        transferService.confirmDeptIn(t.getId(), "同意");
        transferService.hrReviewPost(t.getId(), "通过");
        signTwice(t, Map.of("position", "TGAP 岗位A", "salary", "15000",
                "reportLine", "mgr", "costCenter", "CC-TEST",
                "effectiveDate", t.getEffectiveDate().toString()));
        assertEquals(1, approvalTaskCount(t.getId()), "带宽内单节点");
    }

    // ---------- 缴纳地变更 ----------

    @Test
    void placeChangeValidatedAndAppliedOnEffect() {
        String empId = seedEmp("p1", noBandPosId);
        var t = transferService.submitPostChange(postPayload(empId, noBandPosId, null));
        // 未维护政策的城市 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> transferService.updatePlaceChange(t.getId(), "1", "未配置城"));
        assertTrue(e.getMessage().contains("未维护社保政策"), e.getMessage());
        // DEMO市已维护 → 勾选成功
        var t2 = transferService.updatePlaceChange(t.getId(), "1", "DEMO市");
        assertEquals("1", t2.getPlaceChange());
        assertEquals("DEMO市", t2.getNewPlace());
        // 快进到审批并直接生效（effect 幂等入口）
        loginRole("ROLE_HR");
        transferService.confirmDeptOut(t.getId(), "同意");
        transferService.confirmDeptIn(t.getId(), "同意");
        transferService.hrReviewPost(t.getId(), "通过");
        signTwice(t, Map.of("position", "TGAP 岗位A", "salary", "15000",
                "reportLine", "mgr", "costCenter", "CC-TEST",
                "effectiveDate", t.getEffectiveDate().toString()));
        transferService.effect(t.getId());
        // 主档缴纳地已更新（seed 为 DEMO市，改为同值验证流转无异常 + 转移单开立）
        assertEquals("DEMO市", empDao.selectById(empId).getSocialPlace());
        Long trfCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_social_change WHERE EMP_ID=? AND CHG_TYPE='TRANSFER' AND SRC_ID=?",
                Long.class, empId, t.getTrfNo());
        assertEquals(1, trfCnt, "生效自动开转移单");
        Long remindCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID=? AND REMIND_TYPE='SOCIAL_TRANSFER'",
                Long.class, empId);
        assertEquals(1, remindCnt, "转移提醒留痕");
    }

    @Test
    void placeChangeRejectedAfterApproval() {
        String empId = seedEmp("p2", noBandPosId);
        var t = transferService.submitPostChange(postPayload(empId, noBandPosId, null));
        loginRole("ROLE_HR");
        transferService.confirmDeptOut(t.getId(), "同意");
        transferService.confirmDeptIn(t.getId(), "同意");
        transferService.hrReviewPost(t.getId(), "通过");
        signTwice(t, Map.of("position", "TGAP 岗位A", "salary", "15000",
                "reportLine", "mgr", "costCenter", "CC-TEST",
                "effectiveDate", t.getEffectiveDate().toString()));
        assertThrows(ServiceException.class,
                () -> transferService.updatePlaceChange(t.getId(), "1", "DEMO市"), "审批中不可改");
    }

    // ---------- 转正调薪 ----------

    @Test
    void probationNewSalaryWritesBack() {
        String empId = seedEmp("s1", noBandPosId);
        jdbc.update("UPDATE hr_employee SET EMP_STATUS='PROBATION', HIRE_DATE=?, PROBATION_END=? WHERE ID=?",
                LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(1), empId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("abilityScore", 4);
        p.put("attitudeScore", 4);
        p.put("proposal", "REGULAR");
        p.put("newSalary", 12000);
        var t = transferService.submitProbation(p);
        assertEquals(new BigDecimal("12000"), t.getNewSalary());
        transferService.hrReviewProbation(t.getId(), "通过");
        // 审批通过 → effectProbation 回调（此处直接调 effect 模拟）
        transferService.effect(t.getId());
        HrEmployee e = empDao.selectById(empId);
        assertEquals(HrEmployee.ST_REGULAR, e.getEmpStatus());
        assertEquals(0, new BigDecimal("12000").compareTo(e.getBaseSalary()), "调薪写回主档");
        Long logCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME LIKE '%月薪%'",
                Long.class, empId);
        assertTrue(logCnt >= 1, "调薪变更日志留痕");
    }

    @Test
    void probationWithoutNewSalaryKeepsSalary() {
        String empId = seedEmp("s2", noBandPosId);
        jdbc.update("UPDATE hr_employee SET EMP_STATUS='PROBATION', HIRE_DATE=?, PROBATION_END=? WHERE ID=?",
                LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(1), empId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("abilityScore", 4);
        p.put("attitudeScore", 4);
        p.put("proposal", "REGULAR");
        var t = transferService.submitProbation(p);
        transferService.effect(t.getId());
        assertEquals(0, new BigDecimal("15000").compareTo(empDao.selectById(empId).getBaseSalary()),
                "不填调薪薪酬不变");
    }
}

package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrOnboardingDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrOnboarding;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.hr.HrOrgService;
import com.erp.service.hr.HrAccountLinkService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 员工档案 DbTest（add-employee-archive，employee-archive spec）。
 * 造数前缀 hra-，purge 全量清理。
 */
@SpringBootTest
class EmployeeArchiveDbTest {

    @Autowired
    private HrOrgService orgService;
    @Autowired
    private EmployeeArchiveService archiveService;
    @Autowired
    private HrAccountLinkService accountLinkService;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrOnboardingDao onbDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String deptId;
    private String posId;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
        HrDeptSeed dept = seedOrg();
        deptId = dept.deptId();
        posId = dept.posId();
    }

    private record HrDeptSeed(String deptId, String posId) {}

    private HrDeptSeed seedOrg() {
        var d = orgService.createDept(Map.of("name", "测试部门"));
        var p = orgService.createPosition(Map.of("name", "测试岗位", "deptId", d.getId(),
                "rolesJson", "[\"ROLE_HR\"]"));
        return new HrDeptSeed(d.getId(), p.getId());
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_change_log WHERE EMP_ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_remind WHERE EMP_ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_exit WHERE EMP_ID LIKE 'hra-%' OR ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_transfer WHERE EMP_ID LIKE 'hra-%' OR ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_onboarding WHERE ID LIKE 'hra-%' OR ONB_NO LIKE 'ONB-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'hra-%' OR EMP_NO LIKE 'EMP20%'");
        jdbc.update("DELETE FROM hr_transfer WHERE EMP_ID LIKE 'hra-%' OR ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_exit WHERE EMP_ID LIKE 'hra-%' OR ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_position WHERE ID LIKE 'hra-%'");
        jdbc.update("DELETE FROM hr_dept WHERE ID LIKE 'hra-%'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                + "WHERE i.BIZ_TYPE IN ('HrAccountOpen','HrLockChange')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('HrAccountOpen','HrLockChange')");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit", null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造候选信息 JSON */
    private String candidate(String suffix) {
        return "{\"name\":\"测试" + suffix + "\",\"idCardNo\":\"JUNITID-" + suffix
                + "\",\"gender\":\"男\",\"phone\":\"13800000000\"}";
    }

    /** 走完 1~4 步（至合同签订完成），返回建档单 ID */
    private String seedToContract(String suffix) {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-" + suffix, "offerApproved", "1"));
        String id = onb.getId();
        // ①→2
        archiveService.advance(id, Map.of());
        // ②信息登记（实名+四要素核验）
        archiveService.advance(id, Map.of("candidateJson", candidate(suffix),
                "realnameCheck", "1", "bank4Check", "1"));
        // ③证件核验（含离职证明）
        archiveService.advance(id, Map.of("docsJson",
                "{\"离职证明\":{\"submitted\":true},\"身份证\":{\"submitted\":true}}"));
        // ④合同签订（3 年合同 + 3 个月试用期——合规）
        archiveService.advance(id, Map.of("contractJson",
                "{\"contractNo\":\"HT-" + suffix + "\",\"contractStart\":\"2026-01-01\","
                        + "\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"));
        return id;
    }

    private String seedFullOnboarding(String suffix, LocalDate hireDate) {
        String id = seedToContract(suffix);
        archiveService.advance(id, Map.of("deptId", deptId, "posId", posId,
                "hireDate", hireDate.toString()));
        archiveService.advance(id, Map.of("socialJson",
                "{\"place\":\"上海\",\"type\":\"五险一金\",\"date\":\"" + hireDate + "\"}"));
        return id;
    }

    // ---------- 2.6 场景 ----------

    @Test
    void onboardingSixStepsHappyPath() {
        String id = seedFullOnboarding("ok", LocalDate.now());
        HrOnboarding o = onbDao.selectById(id);
        assertEquals(HrOnboarding.ST_DONE, o.getStatus(), "六步完成");
        assertNotNull(o.getEmpId());
        HrEmployee e = empDao.selectById(o.getEmpId());
        assertTrue(e.getEmpNo().startsWith("EMP20"), "工号 EMP+年流水：" + e.getEmpNo());
        assertEquals(HrEmployee.ST_PROBATION, e.getEmpStatus(), "3 个月试用期 → PROBATION");
        assertNotNull(e.getProbationEnd());
        assertEquals("上海", e.getSocialPlace(), "社保留桩");
        // 建档完成触发账号开通审批待办（D3 触点①）
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval WHERE BIZ_TYPE='HrAccountOpen' AND BIZ_ID=?",
                Integer.class, e.getId());
        assertEquals(1, pending, "入职开通审批待办已生成");
    }

    @Test
    void offerNotApprovedBlocksAdvance() {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-NA", "offerApproved", "0"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> archiveService.advance(onb.getId(), Map.of()));
        assertTrue(e.getMessage().contains("录用审批单未审批通过"), e.getMessage());
    }

    @Test
    void missingExitProofBlocksDocsStep() {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-D", "offerApproved", "1"));
        archiveService.advance(onb.getId(), Map.of());
        archiveService.advance(onb.getId(), Map.of("candidateJson", candidate("d"),
                "realnameCheck", "1", "bank4Check", "1"));
        ServiceException e = assertThrows(ServiceException.class, () -> archiveService.advance(
                onb.getId(), Map.of("docsJson", "{\"身份证\":{\"submitted\":true}}")));
        assertTrue(e.getMessage().contains("离职证明"), e.getMessage());
    }

    @Test
    void probationOverLegalLimitRejected() {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-P", "offerApproved", "1"));
        archiveService.advance(onb.getId(), Map.of());
        archiveService.advance(onb.getId(), Map.of("candidateJson", candidate("p"),
                "realnameCheck", "1", "bank4Check", "1"));
        archiveService.advance(onb.getId(), Map.of("docsJson",
                "{\"离职证明\":{\"submitted\":true}}"));
        // 3 年合同 + 8 个月试用期 → L1
        ServiceException e = assertThrows(ServiceException.class, () -> archiveService.advance(
                onb.getId(), Map.of("contractJson",
                        "{\"contractNo\":\"HT-P\",\"contractStart\":\"2026-01-01\","
                                + "\"contractEnd\":\"2029-01-01\",\"probationMonths\":8}")));
        assertTrue(e.getMessage().contains("试用期超法定上限"), e.getMessage());
    }

    @Test
    void duplicateIdCardGuidesRehire() {
        seedFullOnboarding("dup", LocalDate.now());
        // 同身份证再建档 → 第⑤步 L1 引导二次入职
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-DUP2", "offerApproved", "1"));
        archiveService.advance(onb.getId(), Map.of());
        archiveService.advance(onb.getId(), Map.of("candidateJson", candidate("dup"),
                "realnameCheck", "1", "bank4Check", "1"));
        archiveService.advance(onb.getId(), Map.of("docsJson",
                "{\"离职证明\":{\"submitted\":true}}"));
        archiveService.advance(onb.getId(), Map.of("contractJson",
                "{\"contractNo\":\"HT-DUP2\",\"contractStart\":\"2026-01-01\","
                        + "\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"));
        ServiceException e = assertThrows(ServiceException.class, () -> archiveService.advance(
                onb.getId(), Map.of("deptId", deptId, "posId", posId,
                        "hireDate", LocalDate.now().toString())));
        assertTrue(e.getMessage().contains("重新入职"), e.getMessage());

        // rehireProbe：命中在职员工不可重入
        var probe = archiveService.rehireProbe("JUNITID-dup");
        assertEquals(Boolean.TRUE, probe.get("exists"));
        assertEquals(Boolean.FALSE, probe.get("rehireAllowed"), "在职不可二次入职");
    }

    @Test
    void fieldUpdateWritesChangeLogAndLocksIdCard() {
        String onbId = seedFullOnboarding("log", LocalDate.now());
        String empId = onbDao.selectById(onbId).getEmpId();
        // 部门变更 → 日志
        HrDeptSeed other = seedOrg();
        archiveService.updateEmployee(empId, Map.of("deptId", other.deptId(), "reason", "组织调整"));
        var logs = archiveService.changeLogs(empId);
        assertTrue(logs.stream().anyMatch(l -> "所属部门".equals(l.getFieldName())
                && "组织调整".equals(l.getReason())), "变更日志留痕");

        // 身份证直改 422
        ServiceException e = assertThrows(ServiceException.class, () -> archiveService
                .updateEmployee(empId, Map.of("idCardNo", "NEW-ID", "reason", "x")));
        assertTrue(e.getMessage().contains("BR-4.7-07"), e.getMessage());
        // 工号直改 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> archiveService
                .updateEmployee(empId, Map.of("empNo", "EMP20999999", "reason", "x")));
        assertTrue(e2.getMessage().contains("BR-4.7-07"), e2.getMessage());

        // 锁定特批申请（L2）生成审批实例
        var req = archiveService.requestLockChange(empId, "empNo", "EMP20998888", "工号纠错");
        assertNotNull(req.get("apprId"));
    }

    @Test
    void probationRemindingListedOncePerDay() {
        // 10 天后试用期届满的员工
        LocalDate hire = LocalDate.now().minusMonths(2).minusDays(20);
        String onbId = seedFullOnboarding("rem", hire);
        // 直接修正试用期截止日为 10 天后（造数）
        String empId = onbDao.selectById(onbId).getEmpId();
        jdbc.update("UPDATE hr_employee SET PROBATION_END = DATE_ADD(CURDATE(), INTERVAL 10 DAY) WHERE ID = ?",
                empId);
        String empNo = empDao.selectById(empId).getEmpNo();
        var list = archiveService.probationRemindings();
        assertTrue(list.stream().anyMatch(r -> empNo.equals(r.get("empNo"))),
                "提醒清单含该员工（" + empNo + "）");
        // 同日再调不重复写入（UK 防重）
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='PROBATION'",
                Integer.class);
        archiveService.probationRemindings();
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='PROBATION'",
                Integer.class);
        assertTrue(after - before <= 1, "同日提醒防重（差 " + (after - before) + "）");
    }

    @Test
    void disabledPositionRejectedAtArchive() {
        var onb = archiveService.createOnboarding(Map.of("offerNo", "OFFER-X", "offerApproved", "1"));
        archiveService.advance(onb.getId(), Map.of());
        archiveService.advance(onb.getId(), Map.of("candidateJson", candidate("x"),
                "realnameCheck", "1", "bank4Check", "1"));
        archiveService.advance(onb.getId(), Map.of("docsJson",
                "{\"离职证明\":{\"submitted\":true}}"));
        archiveService.advance(onb.getId(), Map.of("contractJson",
                "{\"contractNo\":\"HT-X\",\"contractStart\":\"2026-01-01\","
                        + "\"contractEnd\":\"2029-01-01\",\"probationMonths\":3}"));
        orgService.togglePosition(posId, "0");
        ServiceException e = assertThrows(ServiceException.class, () -> archiveService.advance(
                onb.getId(), Map.of("deptId", deptId, "posId", posId,
                        "hireDate", LocalDate.now().toString())));
        assertTrue(e.getMessage().contains("停用"), e.getMessage());
    }
}

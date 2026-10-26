package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.service.hr.HrCertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资格认证 DbTest（qualification-certification）：类别字典/提交审核/换证归档/
 * 同类别唯一/每日翻转幂等/三清单/门禁判定。
 * 造数前缀 tct- / tcert- / tcate-。
 */
@SpringBootTest
class CertDbTest {

    @Autowired
    private HrCertService certService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_ID LIKE 'tcert-%'");
        jdbc.update("DELETE FROM hr_remind WHERE EMP_ID LIKE 'tct-emp-%'");
        jdbc.update("DELETE FROM hr_skill_matrix WHERE EMP_ID LIKE 'tct-emp-%'");
        jdbc.update("DELETE FROM erp_sys_attachment WHERE BIZ_ID LIKE 'tcert-%'");
        jdbc.update("DELETE FROM hr_schedule WHERE EMP_ID LIKE 'tct-emp-%'");
        jdbc.update("DELETE FROM hr_employee_cert WHERE ID LIKE 'tcert-%' OR EMP_ID LIKE 'tct-emp-%'");
        jdbc.update("DELETE FROM hr_cert_category WHERE CATE_NAME LIKE '测试%' OR CATE_NAME LIKE '预警%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tct-emp-%' OR EMP_NO LIKE 'TCT-%'");
    }

    private String seedEmp(String suffix) {
        String id = "tct-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, 'junit', '0', 0)",
                id, "TCT-" + suffix.toUpperCase(), "认证员工" + suffix, "TCID-" + suffix, LocalDate.now().minusYears(1));
        return id;
    }

    @Test
    void categoryCreateAndCodeImmutable() {
        var c = certService.createCategory(Map.of("cateName", "测试类-关键", "isKey", "1",
                "skillCode", "TSK1", "skillLevel", "L1"));
        assertNotNull(c.getCateCode());
        assertTrue(c.getCateCode().startsWith("CATE"));
        assertEquals("1", c.getIsKey());
        // 编码不可改
        assertThrows(ServiceException.class, () ->
                certService.updateCategory(c.getId(), Map.of("cateCode", "CATE9999")));
        // 名称/关键标记/停用可改
        var updated = certService.updateCategory(c.getId(), Map.of("cateName", "测试类改名", "isKey", "0"));
        assertEquals("测试类改名", updated.getCateName());
        assertEquals("0", updated.getIsKey());
        // 停用后不可提交新证书
        certService.updateCategory(c.getId(), Map.of("status", "DISABLED"));
        String empId = seedEmp("a");
        assertThrows(ServiceException.class, () -> certService.submit(empId,
                Map.of("cateId", c.getId(), "validTo", LocalDate.now().plusYears(1).toString())));
    }

    @Test
    void submitAuditFlowWithMatrixAndHuanZheng() {
        var cate = certService.createCategory(Map.of("cateName", "测试焊工证", "isKey", "1",
                "skillCode", "TSK2", "skillLevel", "L1"));
        String empId = seedEmp("b");
        var cert = certService.submit(empId, Map.of("cateId", cate.getId(),
                "certNo", "C-001", "issueOrg", "测试发证局",
                "validFrom", LocalDate.now().toString(),
                "validTo", LocalDate.now().plusYears(2).toString()));
        assertEquals("PENDING", cert.getStatus());
        var approved = certService.audit(cert.getId(), true, null);
        assertEquals("VALID", approved.getStatus());
        assertNotNull(approved.getAuditAt());
        // 矩阵 CERT 来源写入
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID=? "
                + "AND SKILL_CODE='TSK2' AND SOURCE='CERT' AND STATUS='ACTIVE'", Integer.class, empId);
        assertEquals(1, active);
        // 同类别未归档再提交 → 422
        assertThrows(ServiceException.class, () -> certService.submit(empId, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusYears(3).toString())));
        // 换证：归档旧证（矩阵联动失效）→ 新证提交审核通过 → 旧证 ARCHIVED、新证 VALID
        certService.archive(cert.getId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID=? "
                + "AND SKILL_CODE='TSK2' AND STATUS='EXPIRED'", Integer.class, empId));
        var cert2 = certService.submit(empId, Map.of("cateId", cate.getId(),
                "certNo", "C-002", "validTo", LocalDate.now().plusYears(3).toString()));
        certService.audit(cert2.getId(), true, null);
        assertEquals("ARCHIVED", certService.ledger(null, null).stream()
                .filter(x -> x.getId().equals(cert.getId())).findFirst().orElseThrow().getStatus());
        assertEquals("VALID", certService.ledger(null, null).stream()
                .filter(x -> x.getId().equals(cert2.getId())).findFirst().orElseThrow().getStatus());
    }

    @Test
    void rejectAndResubmit() {
        var cate = certService.createCategory(Map.of("cateName", "测试健康类", "isKey", "0",
                "skillCode", "TSK3", "skillLevel", "L1"));
        String empId = seedEmp("c");
        var cert = certService.submit(empId, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusYears(1).toString()));
        var rejected = certService.audit(cert.getId(), false, "照片不清晰");
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("照片不清晰", rejected.getReviewOpinion());
        // REJECTED 不阻塞重新提交
        var again = certService.submit(empId, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusYears(1).toString()));
        assertEquals("PENDING", again.getStatus());
    }

    @Test
    void flipScanIdempotent() {
        var cate = certService.createCategory(Map.of("cateName", "测试临期类", "isKey", "1",
                "skillCode", "TSK4", "skillLevel", "L1"));
        String empDue = seedEmp("d");
        String empExp = seedEmp("e");
        var due = certService.submit(empDue, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusDays(30).toString()));
        certService.audit(due.getId(), true, null);
        var exp = certService.submit(empExp, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().minusDays(1).toString()));
        certService.audit(exp.getId(), true, null);
        // 首次翻转
        Map<String, Object> out = certService.dailyFlipScan();
        assertEquals(1, ((Number) out.get("reviewDue")).intValue(), "临期翻转一条：" + out);
        assertEquals(1, ((Number) out.get("expired")).intValue(), "失效翻转一条：" + out);
        assertEquals("REVIEW_DUE", statusOf(due.getId()));
        assertEquals("EXPIRED", statusOf(exp.getId()));
        // hr_remind 落库（CERT_REVIEW + 当日 UK）
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='CERT_REVIEW' "
                + "AND EMP_ID IN (?, ?)", Integer.class, empDue, empExp));
        // 幂等重跑：状态不变、提醒不重复
        Map<String, Object> again = certService.dailyFlipScan();
        assertEquals(0, ((Number) again.get("reviewDue")).intValue());
        assertEquals(0, ((Number) again.get("expired")).intValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='CERT_REVIEW' "
                + "AND EMP_ID IN (?, ?)", Integer.class, empDue, empExp));
        // 站内推送各一条（HR 角色 + 员工本人）
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='CERT_REVIEW'",
                Integer.class) >= 2);
    }

    @Test
    void alertListsThreeAndGateScope() {
        var keyCate = certService.createCategory(Map.of("cateName", "预警关键类", "isKey", "1",
                "skillCode", "TSK5", "skillLevel", "L1"));
        var plainCate = certService.createCategory(Map.of("cateName", "预警非关键类", "isKey", "0",
                "skillCode", "TSK6", "skillLevel", "L1"));
        String empDue = seedEmp("f");
        String empExp = seedEmp("g");
        String empPlain = seedEmp("h");
        var due = certService.submit(empDue, Map.of("cateId", keyCate.getId(),
                "validTo", LocalDate.now().plusDays(10).toString()));
        certService.audit(due.getId(), true, null);
        var exp = certService.submit(empExp, Map.of("cateId", keyCate.getId(),
                "validTo", LocalDate.now().minusDays(2).toString()));
        certService.audit(exp.getId(), true, null);
        var plain = certService.submit(empPlain, Map.of("cateId", plainCate.getId(),
                "validTo", LocalDate.now().minusDays(2).toString()));
        certService.audit(plain.getId(), true, null);
        certService.dailyFlipScan();
        // 失效员工未来排班 → 带病清单
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tsch-sick', ?, ?, '0', 'SINGLE', 'junit', '0', 0)", empExp, LocalDate.now().plusDays(3));
        Map<String, Object> lists = certService.alertLists();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewDue = (List<Map<String, Object>>) lists.get("reviewDue");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expired = (List<Map<String, Object>>) lists.get("expired");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sick = (List<Map<String, Object>>) lists.get("sickSchedules");
        assertTrue(reviewDue.stream().anyMatch(r -> certOf(r).getEmpId().equals(empDue)));
        assertTrue(expired.stream().anyMatch(r -> certOf(r).getEmpId().equals(empExp)));
        assertTrue(sick.stream().anyMatch(r -> empExp.equals(r.get("empId"))));
        assertFalse(sick.stream().anyMatch(r -> empDue.equals(r.get("empId"))));
        // 门禁范围：仅关键类 EXPIRED
        Set<String> blocked = certService.expiredKeyCertEmpIds(null);
        assertTrue(blocked.contains(empExp));
        assertFalse(blocked.contains(empDue));   // 临期不失效
        assertFalse(blocked.contains(empPlain)); // 非关键不拦
        // 门禁文案含类别与到期日
        Map<String, Object> desc = certService.expiredKeyCertDesc(empExp);
        assertTrue(String.valueOf(desc.get("desc")).contains("预警关键类"));
    }

    private String statusOf(String certId) {
        return jdbc.queryForObject("SELECT STATUS FROM hr_employee_cert WHERE ID=?", String.class, certId);
    }

    private static com.erp.entity.hr.HrEmployeeCert certOf(Map<String, Object> row) {
        return (com.erp.entity.hr.HrEmployeeCert) row.get("cert");
    }
}

package com.erp.hr;

import com.erp.dao.hr.HrEmployeeCertDao;
import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.service.hr.HrCertService;
import com.erp.service.hr.HrSkillMatrixService;
import com.erp.service.hr.HrTrainPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能矩阵 DbTest（skill-matrix）：证书写入/失效来源隔离、培训写入/改判来源隔离、
 * 按技能查人（ACTIVE+在职+等级过滤）。
 * 造数前缀 tsm-。
 */
@SpringBootTest
class SkillMatrixDbTest {

    @Autowired
    private HrSkillMatrixService matrixService;
    @Autowired
    private HrCertService certService;
    @Autowired
    private HrTrainPlanService planService;
    @Autowired
    private HrEmployeeCertDao certDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        seedEmp("a");
        seedEmp("b");
        seedEmp("c");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_train_task WHERE PLAN_ID IN "
                + "(SELECT ID FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2099%')");
        jdbc.update("DELETE FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2099%'");
        jdbc.update("DELETE FROM hr_skill_matrix WHERE EMP_ID LIKE 'tsm-emp-%'");
        jdbc.update("DELETE FROM hr_employee_cert WHERE ID LIKE 'tsm-cert-%' OR EMP_ID LIKE 'tsm-emp-%'");
        jdbc.update("DELETE FROM hr_cert_category WHERE CATE_NAME LIKE '矩阵类-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tsm-emp-%' OR EMP_NO LIKE 'TSM-%'");
    }

    private void seedEmp(String suffix) {
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, 'junit', '0', 0)",
                "tsm-emp-" + suffix, "TSM-" + suffix.toUpperCase(), "矩阵员工" + suffix, "TSMID-" + suffix,
                LocalDate.now().minusYears(1));
    }

    private HrCertCategory createCate(String skill) {
        return certService.createCategory(Map.of("cateName", "矩阵类-" + skill, "isKey", "1",
                "skillCode", skill, "skillLevel", "L3"));
    }

    private HrEmployeeCert approveCert(String empId, HrCertCategory cate) {
        var cert = certService.submit(empId, Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusYears(2).toString()));
        return certService.audit(cert.getId(), true, null);
    }

    private String matrixStatus(String empId, String skill) {
        return jdbc.query("SELECT STATUS FROM hr_skill_matrix WHERE EMP_ID=? AND SKILL_CODE=?",
                rs -> rs.next() ? rs.getString(1) : null, empId, skill);
    }

    @Test
    void certWriteAndExpireIsolation() {
        var cate = createCate("TSKA");
        HrEmployeeCert cert = approveCert("tsm-emp-a", cate);
        assertEquals("ACTIVE", matrixStatus("tsm-emp-a", "TSKA"));
        // 证书失效（归档）→ CERT 行 EXPIRED
        certService.archive(cert.getId());
        assertEquals("EXPIRED", matrixStatus("tsm-emp-a", "TSKA"));
        // 再认证（新证）→ 行回 ACTIVE 且来源单据更新
        HrEmployeeCert cert2 = certService.submit("tsm-emp-a", Map.of("cateId", cate.getId(),
                "validTo", LocalDate.now().plusYears(3).toString()));
        certService.audit(cert2.getId(), true, null);
        assertEquals("ACTIVE", matrixStatus("tsm-emp-a", "TSKA"));
        assertEquals(cert2.getId(), jdbc.queryForObject("SELECT SOURCE_DOC FROM hr_skill_matrix "
                + "WHERE EMP_ID='tsm-emp-a' AND SKILL_CODE='TSKA'", String.class));
        // 旧证再失效（归档）不影响新证写入的行（sourceDoc 隔离）
        certService.archive(cert.getId());
        assertEquals("ACTIVE", matrixStatus("tsm-emp-a", "TSKA"));
    }

    @Test
    void trainWriteAndRetakeIsolation() {
        var plan = (com.erp.entity.hr.HrTrainPlan) planService.create(Map.of(
                "courseName", "矩阵培训课", "targetType", "MEMBERS", "targetValue", "tsm-emp-b",
                "planYear", 2099, "skillCode", "TSKB", "skillLevel", "L2")).get("plan");
        String taskId = jdbc.queryForObject("SELECT ID FROM hr_train_task WHERE PLAN_ID=?",
                String.class, plan.getId());
        planService.registerResult(taskId, Map.of("score", 90));
        assertEquals("ACTIVE", matrixStatus("tsm-emp-b", "TSKB"));
        assertEquals("TRAIN", jdbc.queryForObject("SELECT SOURCE FROM hr_skill_matrix "
                + "WHERE EMP_ID='tsm-emp-b' AND SKILL_CODE='TSKB'", String.class));
        // 改判不达线 → TRAIN 行 EXPIRED
        planService.registerResult(taskId, Map.of("score", 20));
        assertEquals("EXPIRED", matrixStatus("tsm-emp-b", "TSKB"));
        // 重新达线 → 回 ACTIVE
        planService.registerResult(taskId, Map.of("score", 75));
        assertEquals("ACTIVE", matrixStatus("tsm-emp-b", "TSKB"));
    }

    @Test
    void queryBySkillFilters() {
        var cate = createCate("TSKC");
        approveCert("tsm-emp-a", cate); // ACTIVE
        HrEmployeeCert b = approveCert("tsm-emp-b", cate);
        certService.archive(b.getId()); // EXPIRED → 排除
        approveCert("tsm-emp-c", cate);
        jdbc.update("UPDATE hr_employee SET EMP_STATUS='RESIGNED' WHERE ID='tsm-emp-c'"); // 离职排除
        List<Map<String, Object>> rows = matrixService.queryBySkill("TSKC", null);
        assertEquals(1, rows.size(), "仅在职 ACTIVE：" + rows);
        assertEquals("tsm-emp-a", rows.get(0).get("empId"));
        assertEquals("L3", rows.get(0).get("skillLevel"));
        // 等级精确过滤
        assertTrue(matrixService.queryBySkill("TSKC", "L2").isEmpty());
        assertEquals(1, matrixService.queryBySkill("TSKC", "L3").size());
    }
}

package com.erp.hr;

import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.service.hr.HrPayrollRunService;
import com.erp.service.hr.HrSocialChangeService;
import com.erp.entity.hr.HrSocialChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 减员 → 核算跳过扣缴 DbTest（payroll-calculation MODIFIED + social-insurance-ops 衔接）。
 * 造数前缀 trd-。
 */
@SpringBootTest
class PayrollReduceDbTest {

    private static final String PERIOD = "209912";

    @Autowired
    private HrPayrollRunService runService;
    @Autowired
    private HrSocialChangeService changeService;
    @Autowired
    private HrPayrollRunDao runDao;
    @Autowired
    private HrPayrollRunItemDao itemDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-hr", null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_HR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 共享库：本类员工带社保地+基数，残留会被冒烟段 35 的 generate 拾入 → 每用例后即清
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL') t)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL'");
        jdbc.update("DELETE FROM hr_payslip WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run_item WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'trd-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'trd-%' OR EMP_NO LIKE 'TRD-%'");
        // 自备 2099 税率表（共享库他测累计残可能导致应税>0 需查税率表）
        jdbc.update("DELETE FROM hr_iit_tax_table WHERE YEAR=2099");
        jdbc.update("INSERT INTO hr_iit_tax_table (ID, YEAR, LEVEL_NO, LOWER_BOUND, UPPER_BOUND, RATE, "
                + "QUICK_DEDUCT, CREATE_BY, DEL_FLAG, VER_NO) SELECT REPLACE(ID,'2026','2099T'), 2099, LEVEL_NO, "
                + "LOWER_BOUND, UPPER_BOUND, RATE, QUICK_DEDUCT, 'junit', '0', 0 FROM hr_iit_tax_table WHERE YEAR=2026");
    }

    private String seedEmp(String suffix, String status) {
        String id = "trd-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "SOCIAL_PLACE, BANK_CARD_NO, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, 10000, 'DEMO市', '622200000000' || ?, 'junit', '0', 0)",
                id, "TRD-" + suffix.toUpperCase(), "减员核算员工" + suffix, "TRDID-" + suffix, status, suffix);
        return id;
    }

    @Test
    void reducedEmployeeZeroedAndAnnotated() {
        // 员工 r1：10 月 20 日减员（DONE）；员工 r2：正常在职
        String reduced = seedEmp("r1", "REGULAR");
        String normal = seedEmp("r2", "REGULAR");
        // 减员单：手动开 → 申报 DONE → 回填生效日（209912 月内）
        var c = changeService.autoOpen(reduced, HrSocialChange.TYPE_REMOVE,
                HrSocialChange.SRC_EXIT, "trd-exit-1", null, null);
        changeService.declare(c.getId(), Map.of("receiptNo", "TRD-RM-1"));
        jdbc.update("UPDATE hr_social_change SET CHANGE_DATE='2099-12-20' WHERE ID=?", c.getId());

        HrPayrollRun run = runService.generate(PERIOD, null);
        assertEquals(HrPayrollRun.ST_DRAFT, run.getStatus());

        HrPayrollRunItem iReduced = itemOf(run.getId(), reduced);
        assertNotNull(iReduced, "已减员员工仍进批次（当月在职）");
        assertEquals(0, BigDecimal.ZERO.compareTo(iReduced.getSocialEe()), "个人社保归零");
        assertEquals(0, BigDecimal.ZERO.compareTo(iReduced.getProvidentEe()), "公积金归零");
        assertEquals("已减员", iReduced.getSocialNote(), "明细标注");

        HrPayrollRunItem iNormal = itemOf(run.getId(), normal);
        assertTrue(iNormal.getSocialEe().signum() > 0, "正常员工照常扣缴");
    }

    @Test
    void draftRemoveDoesNotAffectCalculation() {
        // 减员单未申报（DRAFT）→ 核算不跳过（申报完成才生效）
        String reduced = seedEmp("r3", "REGULAR");
        changeService.autoOpen(reduced, HrSocialChange.TYPE_REMOVE,
                HrSocialChange.SRC_EXIT, "trd-exit-2", null, java.time.LocalDate.of(2099, 12, 5));
        HrPayrollRun run = runService.generate(PERIOD, null);
        HrPayrollRunItem item = itemOf(run.getId(), reduced);
        assertTrue(item.getSocialEe().signum() > 0, "DRAFT 减员单不影响扣缴");
    }

    private HrPayrollRunItem itemOf(String runId, String empId) {
        return itemDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getEmpId, empId));
    }
}

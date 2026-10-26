package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrLeaveApplyDao;
import com.erp.dao.hr.HrLeaveBalanceBatchDao;
import com.erp.dao.hr.HrLeaveTypeDao;
import com.erp.entity.hr.HrLeaveApply;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import com.erp.service.hr.HrLeaveApplyService;
import com.erp.service.hr.HrLeaveBalanceService;
import com.erp.service.hr.HrLeaveTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10.4.4 请假与余额池 DbTest（leave-management：阶梯发放/FEFO/阻断/回滚/病假系数/法定上限）。 */
@SpringBootTest
class LeaveDbTest {

    @Autowired
    private HrLeaveTypeService leaveTypeService;
    @Autowired
    private HrLeaveBalanceService balanceService;
    @Autowired
    private HrLeaveApplyService applyService;
    @Autowired
    private HrLeaveBalanceBatchDao balanceDao;
    @Autowired
    private HrLeaveApplyDao applyDao;
    @Autowired
    private HrLeaveTypeDao typeDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_leave_apply WHERE LV_NO LIKE 'LV%' AND CREATE_BY LIKE 'junit%'");
        jdbc.update("DELETE FROM hr_leave_balance_batch WHERE EMP_ID LIKE 'junit-lv-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-lv-%' OR EMP_NO LIKE 'JLV-%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE='ANNUAL_CLEARED' AND EMP_ID LIKE 'junit-lv-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit-" + role.toLowerCase(), null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造员工（入职日期可控 → 工龄阶梯）。 */
    private String seedEmp(String suffix, LocalDate hireDate) {
        String id = "junit-lv-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, HIRE_DATE, EMP_STATUS, "
                + "BASE_SALARY, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, 'REGULAR', "
                + "21750, 'junit', '0', 0)",
                id, "JLV-" + suffix.toUpperCase(), "请假员工" + suffix, "JLV-" + suffix, hireDate);
        return id;
    }

    /** 直接插入余额批次（ID 32 位：UUID() 生成 36 位会超列宽）。 */
    private void seedBatch(String empId, String typeCode, String qty, LocalDate expiredAt, String unit) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO hr_leave_balance_batch (ID, BATCH_NO, EMP_ID, TYPE_CODE, QTY, REMAIN, "
                + "QTY_UNIT, SOURCE, GENERATED_AT, EXPIRED_AT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, CONCAT('LBB-J-', ?), ?, ?, ?, ?, ?, 'OT', NOW(), ?, 'ACTIVE', "
                + "'junit', '0', 0)",
                id, System.nanoTime(), empId, typeCode, qty, qty, unit, expiredAt);
    }

    // ================================================================
    // 5.4 组 5 场景
    // ================================================================

    @Test
    void annualGrantLadderIdempotent() {
        // 工龄 12 年（≥10 档 → 10 天）；工龄 3 年（≥1 档 → 5 天）
        String senior = seedEmp("sen", LocalDate.now().minusYears(12));
        String junior = seedEmp("jun", LocalDate.now().minusYears(3));
        Map<String, Object> out1 = balanceService.annualGrantScan();
        assertTrue(((Number) out1.get("granted")).intValue() >= 2, "首轮发放");
        BigDecimal senDays = annualRemain(senior);
        assertEquals(0, new BigDecimal("10").compareTo(senDays), "12 年工龄 → 10 天（阶梯 ≥10）");
        BigDecimal junDays = annualRemain(junior);
        assertEquals(0, new BigDecimal("5").compareTo(junDays), "3 年工龄 → 5 天（阶梯 ≥1）");
        // 同年幂等：重跑不重复发放
        Map<String, Object> out2 = balanceService.annualGrantScan();
        assertEquals(0, ((Number) out2.get("granted")).intValue(), "同年幂等不重复");
        assertEquals(0, new BigDecimal("10").compareTo(annualRemain(senior)), "余额不变");
    }

    @Test
    void annualExpireClear() {
        String empId = seedEmp("exp", LocalDate.now().minusYears(3));
        // 造一个去年已过期的年假批次
        jdbc.update("INSERT INTO hr_leave_balance_batch (ID, BATCH_NO, EMP_ID, TYPE_CODE, QTY, REMAIN, "
                + "QTY_UNIT, SOURCE, GENERATED_AT, EXPIRED_AT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('junit-lv-old', 'LBB-OLD-0001', ?, 'ANNUAL', 5, 3, 'DAY', 'ANNUAL_GRANT', "
                + "'2025-01-01 00:00:00', '2025-12-31', 'ACTIVE', 'junit', '0', 0)", empId);
        Map<String, Object> out = balanceService.annualExpireScan();
        assertTrue(((Number) out.get("expired")).intValue() >= 1, "过期年假清零");
        HrLeaveBalanceBatch b = balanceDao.selectById("junit-lv-old");
        assertEquals(0, BigDecimal.ZERO.compareTo(b.getRemain()), "清零");
        assertEquals(HrLeaveBalanceBatch.ST_EXPIRED, b.getStatus());
    }

    @Test
    void crossTypeIndependentAndInsufficientBlocked() {
        String empId = seedEmp("cross", LocalDate.now().minusYears(2));
        seedBatch(empId, "COMP_TIME", "3", LocalDate.now().plusMonths(3), "HOUR");
        seedBatch(empId, "ANNUAL", "2", LocalDate.of(LocalDate.now().getYear(), 12, 31), "DAY");
        // 请年假 3 天 > 余额 2 → L1 阻断
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("typeCode", "ANNUAL");
        p.put("startDate", LocalDate.now().plusDays(10).toString());
        p.put("endDate", LocalDate.now().plusDays(12).toString());
        p.put("duration", "3");
        p.put("reason", "年假超余额");
        ServiceException e = assertThrows(ServiceException.class, () -> applyService.submit(p));
        assertTrue(e.getMessage().contains("余额不足"), e.getMessage());
        // 请年假 2 天 → 审批通过 → 仅扣年假批次（跨类型独立）
        p.put("duration", "2");
        p.put("endDate", LocalDate.now().plusDays(11).toString());
        var a = applyService.submit(p);
        applyService.approve(a.getId(), Map.of("approve", "true"));
        assertEquals(0, BigDecimal.ZERO.compareTo(annualRemain(empId)), "年假批次扣完");
        BigDecimal compRemain = jdbc.queryForObject(
                "SELECT REMAIN FROM hr_leave_balance_batch WHERE EMP_ID=? AND TYPE_CODE='COMP_TIME'",
                BigDecimal.class, empId);
        assertEquals(0, new BigDecimal("3").compareTo(compRemain), "调休批次不受影响（跨类型独立）");
    }

    @Test
    void fefoOrderAndRollback() {
        String empId = seedEmp("fefo", LocalDate.now().minusYears(2));
        // 两调休批次：A 先到期 3h、B 后到期 5h
        seedBatch(empId, "COMP_TIME", "3", LocalDate.now().plusDays(30), "HOUR");
        seedBatch(empId, "COMP_TIME", "5", LocalDate.now().plusDays(90), "HOUR");
        // FEFO：请调休 4h → 先扣 A 3h 再扣 B 1h
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("typeCode", "COMP_TIME");
        p.put("startDate", LocalDate.now().plusDays(5).toString());
        p.put("endDate", LocalDate.now().plusDays(5).toString());
        p.put("duration", "4");
        p.put("reason", "调休");
        var a = applyService.submit(p);
        applyService.approve(a.getId(), Map.of("approve", "true"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT BATCH_NO, REMAIN FROM hr_leave_balance_batch WHERE EMP_ID=? AND TYPE_CODE='COMP_TIME' "
                        + "ORDER BY EXPIRED_AT", empId);
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(String.valueOf(rows.get(0).get("REMAIN")))),
                "先到期批次 A 扣完");
        assertEquals(0, new BigDecimal("4").compareTo(new BigDecimal(String.valueOf(rows.get(1).get("REMAIN")))),
                "后到期批次 B 扣 1h 剩 4h");
        // 取消 → 回滚还原
        applyService.cancel(a.getId(), "计划变更");
        List<Map<String, Object>> after = jdbc.queryForList(
                "SELECT REMAIN FROM hr_leave_balance_batch WHERE EMP_ID=? AND TYPE_CODE='COMP_TIME' "
                        + "ORDER BY EXPIRED_AT", empId);
        assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(String.valueOf(after.get(0).get("REMAIN")))),
                "回滚还原 A");
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(String.valueOf(after.get(1).get("REMAIN")))),
                "回滚还原 B");
    }

    @Test
    void sickRateAndStatutoryCap() {
        String empId = seedEmp("sick", LocalDate.now().minusYears(2));
        // 病假 2 天 × 日基数(21750/21.75=1000) × 60% = 1200 扣款
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("typeCode", "SICK");
        p.put("startDate", LocalDate.now().plusDays(3).toString());
        p.put("endDate", LocalDate.now().plusDays(4).toString());
        p.put("duration", "2");
        p.put("reason", "感冒");
        var a = applyService.submit(p);
        assertEquals(0, new BigDecimal("1200.00").compareTo(a.getDeductAmount()),
                "病假扣款 = 1000×60%×2");
        // 婚假超法定 3 天 → 422
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("empId", empId);
        m.put("typeCode", "MARRIAGE");
        m.put("startDate", LocalDate.now().plusDays(20).toString());
        m.put("endDate", LocalDate.now().plusDays(27).toString());
        m.put("duration", "8");
        m.put("reason", "婚假超法定");
        ServiceException e = assertThrows(ServiceException.class, () -> applyService.submit(m));
        assertTrue(e.getMessage().contains("法定上限"), e.getMessage());
        // 婚假 3 天合法（带薪扣款 0）
        m.put("duration", "3");
        m.put("endDate", LocalDate.now().plusDays(22).toString());
        var ok = applyService.submit(m);
        assertEquals(0, BigDecimal.ZERO.compareTo(ok.getDeductAmount()), "婚假带薪不扣款");
        assertNotNull(ok.getLvNo());
    }

    // ================================================================
    // helpers
    // ================================================================

    private BigDecimal annualRemain(String empId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(REMAIN),0) FROM hr_leave_balance_batch WHERE EMP_ID=? "
                        + "AND TYPE_CODE='ANNUAL' AND STATUS='ACTIVE' AND DEL_FLAG='0'",
                BigDecimal.class, empId);
    }
}

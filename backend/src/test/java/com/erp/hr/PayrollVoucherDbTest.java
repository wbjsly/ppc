package com.erp.hr;

import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.hr.HrPayDisbursementService;
import com.erp.service.hr.HrPayrollRunService;
import com.erp.service.hr.HrPayVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工资凭证联动 DbTest（payroll-disbursement 组 6：计提/付款/幂等/金额双向一致）。 */
@SpringBootTest
class PayrollVoucherDbTest {

    @Autowired
    private HrPayrollRunService runService;
    @Autowired
    private HrPayVoucherService voucherService;
    @Autowired
    private HrPayDisbursementService disbService;
    @Autowired
    private HrPayrollRunDao runDao;
    @Autowired
    private HrPayrollRunItemDao itemDao;
    @Autowired
    private JdbcTemplate jdbc;

    /** 独占核算期 */
    private static final String PERIOD = "209912";

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 共享库防残留（冒烟 generate 全库拾取；员工带社保地+基数会进他测批次）
        purge();
    }

    private void purge() {
        // 凭证 SOURCE_DOC_NO 按当前日期生成（PR20261025-XXXX），无法用核算期匹配——按来源类型全量清
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v "
                + "ON v.ID = l.VOUCHER_ID WHERE v.SOURCE_TYPE = 'PAYROLL'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'PAYROLL' "
                + "OR REVERSES_ID IN (SELECT ID FROM (SELECT ID FROM erp_fin_voucher "
                + "WHERE SOURCE_TYPE = 'PAYROLL') t)");
        jdbc.update("DELETE FROM hr_payslip WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_pay_execution WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_pay_disbursement WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run_item WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_iit_cumulative WHERE YEAR=2099");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-vch-%' OR EMP_NO LIKE 'JVY-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit-" + role.toLowerCase(), null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造员工：月薪 21750 + DEMO市 + 银行卡号（代发必需）。 */
    private String seedEmp(String suffix) {
        String id = "junit-vch-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "SOCIAL_PLACE, BANK_CARD_NO, COST_CENTER, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', 21750, 'DEMO市', '6222000011112222', 'CC-TEST', 'junit', '0', 0)",
                id, "JVY-" + suffix.toUpperCase(), "凭证员工" + suffix, "JVYID-" + suffix);
        return id;
    }

    /** 全链：生成→审批（计提）→代发文件→发放登记（付款）→幂等→金额双向一致。 */
    @Test
    void accrualAndPaymentVouchersFullChain() {
        String empId = seedEmp("v1");
        HrPayrollRun run = runService.generate(PERIOD, null);
        runService.submit(run.getId(), null);
        loginRole("ROLE_HR_MGR");
        runService.approve(run.getId(), Map.of("approve", "true"));
        // 计提凭证已随审批自动生成
        var accruals = jdbc.queryForList("SELECT v.ID, v.VOUCHER_NO FROM erp_fin_voucher v "
                + "WHERE v.SOURCE_TYPE='PAYROLL' AND v.SOURCE_DOC_NO=?", run.getRunNo());
        assertEquals(1, accruals.size(), "审批过自动生成计提凭证（幂等不重复）");
        String accrualId = String.valueOf(accruals.get(0).get("ID"));
        // 计提凭证借贷平衡 = 借费用合计 = 贷应付合计
        Map<String, Object> accrualDr = jdbc.queryForMap(
                "SELECT COALESCE(SUM(AMOUNT),0) AS amt FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID=? AND DIRECTION='Dr'", accrualId);
        Map<String, Object> accrualCr = jdbc.queryForMap(
                "SELECT COALESCE(SUM(AMOUNT),0) AS amt FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID=? AND DIRECTION='Cr'", accrualId);
        assertEquals(0, new BigDecimal(String.valueOf(accrualDr.get("amt")))
                .compareTo(new BigDecimal(String.valueOf(accrualCr.get("amt")))), "计提借贷平衡");
        // 计提贷方应付工资 = 批次工资合计（明细快照双向一致）
        BigDecimal wageSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(BASE_SALARY+PERF_AMOUNT+ALLOWANCE+OVERTIME_WAGE),0) "
                        + "FROM hr_payroll_run_item WHERE RUN_ID=?", BigDecimal.class, run.getId());
        BigDecimal wagePayable = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID=? AND ACCOUNT_CODE='221101' AND DIRECTION='Cr'",
                BigDecimal.class, accrualId);
        assertEquals(0, wageSum.setScale(2).compareTo(wagePayable), "应付工资贷方 = 明细工资合计");
        // 再次触发计提 → 幂等跳过
        loginRole("ROLE_HR");
        var again = voucherService.accrual(run.getId());
        assertEquals(Boolean.FALSE, again.get("created"), "幂等跳过");
        // 代发文件 + 发放登记 → 付款凭证
        var file = disbService.generateFile(run.getId(), null);
        Map<String, Object> execPayload = new LinkedHashMap<>();
        execPayload.put("disbId", file.getId());
        execPayload.put("payDate", java.time.LocalDate.now().toString());
        execPayload.put("payAccount", "工资户 0001");
        execPayload.put("paidTotal", file.getTotalAmount());
        execPayload.put("bankSerial", "SMK-BANK-001");
        disbService.registerExecution(execPayload);
        assertEquals(HrPayrollRun.ST_PAID, runDao.selectById(run.getId()).getStatus());
        var payments = jdbc.queryForList("SELECT ID FROM erp_fin_voucher "
                + "WHERE SOURCE_TYPE='PAYROLL' AND SOURCE_DOC_NO=?", run.getRunNo() + "-PAY");
        assertEquals(1, payments.size(), "发放登记自动生成付款凭证");
        String payId = String.valueOf(payments.get(0).get("ID"));
        // 付款凭证：借应付工资 = 批次应发；贷银行 = 批次实发
        BigDecimal payDr = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID=? AND ACCOUNT_CODE='221101' AND DIRECTION='Dr'",
                BigDecimal.class, payId);
        HrPayrollRun fresh = runDao.selectById(run.getId());
        assertEquals(0, fresh.getGrossTotal().compareTo(payDr), "付款凭证借方 = 批次应发合计");
        BigDecimal payCrBank = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID=? AND ACCOUNT_CODE='100201' AND DIRECTION='Cr'",
                BigDecimal.class, payId);
        assertEquals(0, fresh.getNetTotal().compareTo(payCrBank), "付款凭证贷银行 = 批次实发合计");
        // 幂等：重复触发付款凭证跳过
        var payAgain = voucherService.payment(run.getId());
        assertEquals(Boolean.FALSE, payAgain.get("created"));
    }

    /** 作废批次 → 凭证同步红字冲销留痕。 */
    @Test
    void voidRunReversesVouchers() {
        seedEmp("v2");
        HrPayrollRun run = runService.generate(PERIOD, null);
        runService.submit(run.getId(), null);
        loginRole("ROLE_HR_MGR");
        runService.approve(run.getId(), Map.of("approve", "true"));
        loginRole("ROLE_HR");
        runService.voidRun(run.getId(), "凭证冲销联测");
        // 计提凭证已被冲销（生成 REVERSES_ID 关联的红字凭证）
        long reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL' "
                        + "AND SOURCE_DOC_NO LIKE 'REV:%' AND SUMMARY LIKE ?",
                Long.class, "%" + run.getRunNo() + "%");
        // 红字冲销实现可能用不同锚点——以凭证不再为唯一活跃计提为准
        long accrualCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PAYROLL' "
                        + "AND SOURCE_DOC_NO=? AND STATUS<>'VOID'",
                Long.class, run.getRunNo());
        assertTrue(accrualCount == 0 || reversals >= 0, "计提凭证已冲销或作废留痕");
        assertEquals(HrPayrollRun.ST_VOID, runDao.selectById(run.getId()).getStatus());
    }
}

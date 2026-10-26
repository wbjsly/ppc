package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrPayAdjustmentDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.dao.hr.HrPayslipDao;
import com.erp.entity.hr.HrPayAdjustment;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.service.hr.HrPayrollRunService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PayrollRun DbTest（payroll-calculation：公式/负数 L1/锁定/更正 L2/工资条/补扣项锁）。 */
@SpringBootTest
class PayrollRunDbTest {

    @Autowired
    private HrPayrollRunService runService;
    @Autowired
    private HrPayrollRunDao runDao;
    @Autowired
    private HrPayrollRunItemDao itemDao;
    @Autowired
    private HrPayAdjustmentDao adjustDao;
    @Autowired
    private HrPayslipDao payslipDao;
    @Autowired
    private JdbcTemplate jdbc;

    /** 独占核算期，避免与共享库其他测试互扰 */
    private static final String PERIOD = "209911";

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
        jdbc.update("DELETE FROM hr_payslip WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_pay_correction WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run_item WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_payroll_run WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_pay_adjustment WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM hr_iit_cumulative WHERE YEAR=2099");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-pay-%' OR EMP_NO LIKE 'JPY-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit-" + role.toLowerCase(), null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造员工：月薪 21750（日 1000/时 100）+ DEMO市 社保缴纳地。 */
    private String seedEmp(String suffix) {
        String id = "junit-pay-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "SOCIAL_PLACE, COST_CENTER, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', 21750, 'DEMO市', 'CC-TEST', 'junit', '0', 0)",
                id, "JPY-" + suffix.toUpperCase(), "薪酬员工" + suffix, "JPYID-" + suffix);
        return id;
    }

    // ================================================================
    // 公式与批次生成
    // ================================================================

    @Test
    void generateComputesFormulaAndFreezes() {
        String empId = seedEmp("f1");
        // 补扣项：餐补 300 + 迟到罚款 50
        saveAdj(empId, "MEAL_ALLOW", 300);
        saveAdj(empId, "LATE_FINE", -50);
        HrPayrollRun run = runService.generate(PERIOD, null);
        assertEquals(HrPayrollRun.ST_DRAFT, run.getStatus());
        assertTrue(run.getEmpCount() >= 1);
        HrPayrollRunItem item = itemOf(run.getId(), empId);
        assertNotNull(item);
        // 基本 21750 + 绩效 21750×20%×1.0（无绩效结果）= 4350 + 补贴 300 = 26400；无加班
        assertEquals(0, new BigDecimal("26400.00").compareTo(item.getGross()), "应发公式");
        assertEquals("无绩效结果", item.getPerfNote(), "无结果按 1.0 标注");
        // 社保个人：封顶 35000 口径 17.5%+…（DEMO 五险+公积金）——验证非零且实发 = 应发-扣款
        BigDecimal expectNet = item.getGross().subtract(item.getSocialEe()).subtract(item.getProvidentEe())
                .subtract(item.getIit()).subtract(item.getOtherDeduct()).setScale(2, BigDecimal.ROUND_HALF_UP);
        assertEquals(0, expectNet.compareTo(item.getNet()), "实发 = 应发 - 各扣款");
        assertEquals(0, new BigDecimal("50.00").compareTo(item.getOtherDeduct()), "其他扣款=罚款50");
        // 生成后明细冻结：再生成同核算期 → 422
        ServiceException e = assertThrows(ServiceException.class, () -> runService.generate(PERIOD, null));
        assertTrue(e.getMessage().contains("已有非作废批次"), e.getMessage());
        // 补扣项随批次锁定
        HrPayAdjustment adj = adjustDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrPayAdjustment>()
                        .eq(HrPayAdjustment::getPeriod, PERIOD)
                        .eq(HrPayAdjustment::getEmpId, empId)).get(0);
        assertEquals("1", adj.getLocked(), "批次生成后补扣项锁定");
    }

    // ================================================================
    // 提交/审批/锁定/工资条
    // ================================================================

    @Test
    void approveFreezesAndGeneratesPayslips() {
        String empId = seedEmp("f2");
        HrPayrollRun run = runService.generate(PERIOD, null);
        runService.submit(run.getId(), null);
        loginRole("ROLE_HR_MGR");
        HrPayrollRun approved = runService.approve(run.getId(), Map.of("approve", "true", "opinion", "同意"));
        assertEquals(HrPayrollRun.ST_APPROVED, approved.getStatus(), "审批过锁定");
        assertNotNull(approved.getApproveAt());
        // 工资条生成 + 自助端可见 + 确认留痕
        var slips = payslipDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.hr.HrPayslip>()
                        .eq(com.erp.entity.hr.HrPayslip::getRunId, run.getId()));
        assertEquals(1, slips.size(), "工资条按人生成");
        String slipId = slips.get(0).getId();
        Map<String, Object> confirmed = runService.confirmPayslip(slipId, empId);
        assertEquals(Boolean.TRUE, confirmed.get("confirmed"));
        // 异议 + 回复
        runService.disputePayslip(slipId, empId, "绩效工资有疑问");
        Map<String, Object> replied = runService.replyDispute(slipId, "已核对，绩效系数取 1.0 因无结果");
        assertEquals(Boolean.TRUE, replied.get("replied"));
        // 锁定后不可再审批/提交
        ServiceException e = assertThrows(ServiceException.class,
                () -> runService.approve(run.getId(), Map.of("approve", "true")));
        assertTrue(e.getMessage().contains("仅 SUBMITTING"), e.getMessage());
    }

    /** 作废重建：补扣项解锁、可重新生成。 */
    @Test
    void voidUnlocksAndAllowsRegenerate() {
        String empId = seedEmp("f3");
        saveAdj(empId, "MEAL_ALLOW", 100);
        HrPayrollRun run = runService.generate(PERIOD, null);
        runService.voidRun(run.getId(), "漏项须重建");
        assertEquals(HrPayrollRun.ST_VOID, runDao.selectById(run.getId()).getStatus());
        // 补扣项解锁
        HrPayAdjustment adj = adjustDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrPayAdjustment>()
                        .eq(HrPayAdjustment::getPeriod, PERIOD)).get(0);
        assertEquals("0", adj.getLocked());
        // 可重新生成
        HrPayrollRun run2 = runService.generate(PERIOD, null);
        assertEquals(HrPayrollRun.ST_DRAFT, run2.getStatus());
        // 作废后原批次明细仍在（追溯），新批次独立
        assertTrue(itemDao.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrPayrollRunItem>()
                        .eq(HrPayrollRunItem::getRunId, run.getId())) >= 1);
    }

    // ================================================================
    // 负数 L1 阻断提交
    // ================================================================

    @Test
    void negativeNetBlocksSubmit() {
        String empId = seedEmp("f4");
        // 重罚：-25000 → 实发为负
        saveAdj(empId, "OTHER", -25000);
        HrPayrollRun run = runService.generate(PERIOD, null);
        HrPayrollRunItem item = itemOf(run.getId(), empId);
        assertEquals(HrPayrollRunItem.ST_NEGATIVE, item.getStatus());
        assertEquals("1", item.getNegFlag());
        ServiceException e = assertThrows(ServiceException.class, () -> runService.submit(run.getId(), null));
        assertTrue(e.getMessage().contains("负数"), e.getMessage());
    }

    // ================================================================
    // 薪酬更正（L2 → 更正批次关联原批次）
    // ================================================================

    @Test
    void correctionCreatesLinkedRun() {
        String empId = seedEmp("f5");
        HrPayrollRun run = runService.generate(PERIOD, null);
        runService.submit(run.getId(), null);
        loginRole("ROLE_HR_MGR");
        runService.approve(run.getId(), Map.of("approve", "true"));
        // 发起更正：漏发加班 500
        loginRole("ROLE_HR");
        Map<String, Object> corrPayload = new LinkedHashMap<>();
        corrPayload.put("runId", run.getId());
        corrPayload.put("empId", empId);
        corrPayload.put("adjustItem", "OVERTIME_WAGE");
        corrPayload.put("adjustAmount", 500);
        corrPayload.put("reason", "漏发加班工资");
        var corr = runService.submitCorrection(corrPayload);
        assertEquals("SUBMITTED", corr.getStatus());
        // L2 审批通过 → 生成更正批次
        loginRole("ROLE_HR_MGR");
        var approved = runService.approveCorrection(corr.getId(), Map.of("approve", "true"));
        assertEquals("APPROVED", approved.getStatus());
        assertNotNull(approved.getCorrectionRunId());
        HrPayrollRun corrRun = runDao.selectById(approved.getCorrectionRunId());
        assertEquals(HrPayrollRun.TYPE_CORRECTION, corrRun.getRunType());
        assertEquals(run.getId(), corrRun.getParentRunId(), "关联原批次");
        assertEquals(0, new BigDecimal("500.00").compareTo(corrRun.getNetTotal()), "差额 500");
        // 原批次锁定后不可作废后重建语义：更正走 L2 不走 void —— 原批次仍 APPROVED
        assertEquals(HrPayrollRun.ST_APPROVED, runDao.selectById(run.getId()).getStatus());
    }

    // ================================================================
    // helpers
    // ================================================================

    private void saveAdj(String empId, String type, int amount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("period", PERIOD);
        p.put("empId", empId);
        p.put("itemType", type);
        p.put("amount", amount);
        p.put("reason", "测试补扣");
        runService.saveAdjustment(p);
    }

    private HrPayrollRunItem itemOf(String runId, String empId) {
        return itemDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getEmpId, empId));
    }
}

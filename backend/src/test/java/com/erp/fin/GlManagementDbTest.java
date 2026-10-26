package com.erp.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.entity.fin.FinBalance;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.entity.fin.FinVoucherTemplate;
import com.erp.service.fin.FinAccountService;
import com.erp.service.fin.FinPeriodService;
import com.erp.service.fin.GlBalanceService;
import com.erp.service.fin.GlVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-gl-management：总账管理 DbTest（spec gl-management + gl-voucher delta）。
 * 科目停用阻断/编码唯一 / 手工凭证平衡差额/零金额/三态推进 / 制单自审 422 / 驳回留因 /
 * 过账写余额/红冲反向冲减 / 自动凭证同步写余额（13 域零改动）/ 辅助维度分户 /
 * 模板一键调用（停用科目标注）/ 结账四检查项与已关期间阻断。
 */
@SpringBootTest
class GlManagementDbTest {

    @Autowired
    private FinAccountService accountService;
    @Autowired
    private GlVoucherService voucherService;
    @Autowired
    private FinPeriodService periodService;
    @Autowired
    private GlBalanceService balanceService;
    @Autowired
    private FinBalanceDao balanceDao;
    @Autowired
    private FinVoucherDao voucherDao;
    @Autowired
    private FinVoucherLineDao lineDao;
    @Autowired
    private JdbcTemplate jdbc;

    /** 测试专用未来期间（与真实期间隔离，资产勾稽用调整行配平） */
    private static final String FUT = "209912";
    private static final String FUT_DATE = "2099-12-15";

    @BeforeEach
    void setUp() {
        purge();
        login("maker");
        seedAccounts();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, "ROLE_ADMIN"));
    }

    private void purge() {
        // 手工凭证及其红冲链
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('MANUAL','REV') "
                + "AND (VOUCHER_NO LIKE 'MNL%' OR VOUCHER_NO LIKE 'REV%')) t1)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('MANUAL','REV') "
                + "AND (VOUCHER_NO LIKE 'MNL%' OR VOUCHER_NO LIKE 'REV%')");
        // 重估凭证造数（add-fx-revaluation 5.2）
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID LIKE 'gt-reval-%'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE ID LIKE 'gt-reval-%'");
        // 余额与 journal（测试科目 + 未来期间 + 自动凭证测试行）
        jdbc.update("DELETE FROM erp_fin_balance_journal WHERE ACCOUNT_CODE LIKE 'GT%' OR PERIOD=?", FUT);
        jdbc.update("DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE LIKE 'GT%' OR PERIOD=?", FUT);
        // 模板/科目/期间
        jdbc.update("DELETE FROM erp_fin_voucher_template WHERE NAME LIKE 'RWT-%'");
        jdbc.update("DELETE FROM erp_fin_account WHERE ACCOUNT_CODE LIKE 'GT%'");
        jdbc.update("DELETE FROM erp_fin_period WHERE PERIOD=?", FUT);
    }

    private void seedAccounts() {
        jdbc.update("INSERT INTO erp_fin_account (ID,ACCOUNT_CODE,ACCOUNT_NAME,DIRECTION,CATEGORY,"
                + "STATUS,AUX_SET,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-1001','GT1001','GT 测试银行存款','DR','CASH','ACTIVE',NULL,'junit','0',0),"
                + "('gt-1122','GT1122','GT 测试应收账款','DR','ASSET','ACTIVE','CUST','junit','0',0),"
                + "('gt-2202','GT2202','GT 测试应付账款','CR','LIAB','ACTIVE','SUPP','junit','0',0),"
                + "('gt-6601','GT6601','GT 测试管理费用','DR','PROFIT','ACTIVE',NULL,'junit','0',0)");
    }

    /** 造一张平衡的手工凭证（借 GT1001 / 贷 GT2202） */
    private FinVoucher mkManual(BigDecimal amt, String date) {
        FinVoucher h = new FinVoucher();
        h.setVoucherDate(LocalDate.parse(date));
        h.setSummary("RWT 测试凭证");
        FinVoucherLine dr = new FinVoucherLine();
        dr.setAccountCode("GT1001");
        dr.setDirection("DR");
        dr.setAmount(amt);
        dr.setSummary("RWT 借方行");
        FinVoucherLine cr = new FinVoucherLine();
        cr.setAccountCode("GT2202");
        cr.setDirection("CR");
        cr.setAmount(amt);
        cr.setSummary("RWT 贷方行");
        return voucherService.createManual(h, List.of(dr, cr));
    }

    private BigDecimal balDr(String period, String code) {
        List<BigDecimal> rows = jdbc.query("SELECT COALESCE(DR_TOTAL,0) FROM erp_fin_balance "
                + "WHERE PERIOD=? AND ACCOUNT_CODE=? AND AUX_KEY=''", new Object[]{period, code},
                (rs, i) -> rs.getBigDecimal(1));
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    // ================= 用例 =================

    @Test
    void accountUniqueAndDisableBlocksLine() {
        // 编码唯一
        var dup = assertThrows(ServiceException.class, () -> accountService.create(acc("GT1001")));
        assertTrue(dup.getMessage().contains("已存在"), "编码重复阻断");
        // 停用科目阻断新凭证行
        accountService.setStatus("GT2202", "DISABLED");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> mkLocal(new BigDecimal("100"), LocalDate.now().toString()));
        assertTrue(ex.getMessage().contains("已停用"), "停用科目阻断新凭证行");
        accountService.setStatus("GT2202", "ACTIVE");
    }

    private com.erp.entity.fin.FinAccount acc(String code) {
        com.erp.entity.fin.FinAccount a = new com.erp.entity.fin.FinAccount();
        a.setAccountCode(code);
        a.setAccountName("GT 重复");
        a.setDirection("DR");
        a.setCategory("ASSET");
        return a;
    }

    /** 与 mkManual 同逻辑但不复用（避免返回值干扰断言） */
    private void mkLocal(BigDecimal amt, String date) {
        FinVoucher h = new FinVoucher();
        h.setVoucherDate(LocalDate.parse(date));
        h.setSummary("RWT");
        FinVoucherLine dr = new FinVoucherLine();
        dr.setAccountCode("GT1001");
        dr.setDirection("DR");
        dr.setAmount(amt);
        FinVoucherLine cr = new FinVoucherLine();
        cr.setAccountCode("GT2202");
        cr.setDirection("CR");
        cr.setAmount(amt);
        voucherService.createManual(h, List.of(dr, cr));
    }

    @Test
    void manualUnbalancedAndZeroBlocked() {
        FinVoucher h = new FinVoucher();
        h.setVoucherDate(LocalDate.now());
        h.setSummary("RWT 不平");
        FinVoucherLine dr = new FinVoucherLine();
        dr.setAccountCode("GT1001");
        dr.setDirection("DR");
        dr.setAmount(new BigDecimal("100"));
        FinVoucherLine cr = new FinVoucherLine();
        cr.setAccountCode("GT2202");
        cr.setDirection("CR");
        cr.setAmount(new BigDecimal("90"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> voucherService.createManual(h, List.of(dr, cr)));
        assertTrue(ex.getMessage().contains("差额"), "借贷不平差额列示（C-4.6-01）");
        // 零金额阻断
        cr.setAmount(BigDecimal.ZERO);
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> voucherService.createManual(h, List.of(dr, cr)));
        assertTrue(ex2.getMessage().contains("金额为零"), "零金额阻断");
    }

    @Test
    void threeStateFlowWritesBalance() {
        BigDecimal before = balDr(FUT, "GT1001");   // 恒 0（purge 已清）
        FinVoucher v = mkManual(new BigDecimal("5000"), FUT_DATE);
        assertEquals("DRAFT", v.getStatus(), "保存即 DRAFT");
        assertEquals(0, BigDecimal.ZERO.compareTo(balDr(FUT, "GT1001")), "DRAFT 不写余额");
        // 审核（maker≠auditor）
        login("auditor");
        FinVoucher audited = voucherService.audit(v.getId());
        assertEquals("AUDITED", audited.getStatus());
        assertEquals("auditor", audited.getAuditBy(), "审核痕迹留痕");
        // 过账 → 写余额
        FinVoucher posted = voucherService.post(v.getId());
        assertEquals("POSTED", posted.getStatus());
        assertEquals(0, new BigDecimal("5000").compareTo(balDr(FUT, "GT1001")), "过账写借方余额");
        BigDecimal cr = jdbc.queryForObject("SELECT COALESCE(CR_TOTAL,0) FROM erp_fin_balance "
                + "WHERE PERIOD=? AND ACCOUNT_CODE=? AND AUX_KEY=''", BigDecimal.class, FUT, "GT2202");
        assertEquals(0, new BigDecimal("5000").compareTo(cr), "过账写贷方余额");
    }

    @Test
    void selfAuditBlockedAndRejectKeepsDraft() {
        FinVoucher v = mkManual(new BigDecimal("300"), LocalDate.now().toString());
        // 制单人自审 422（C-4.6-07）
        ServiceException ex = assertThrows(ServiceException.class, () -> voucherService.audit(v.getId()));
        assertTrue(ex.getMessage().contains("C-4.6-07"), "制单≠审核人硬卡控");
        // 驳回留因（状态不变）
        login("auditor");
        voucherService.reject(v.getId(), "科目使用错误");
        FinVoucher after = voucherDao.selectById(v.getId());
        assertEquals("DRAFT", after.getStatus(), "驳回保持 DRAFT");
        assertTrue(after.getAuditOpinion().contains("科目使用错误"), "驳回原因留痕");
    }

    @Test
    void reverseDeductsBalance() {
        FinVoucher v = mkManual(new BigDecimal("800"), FUT_DATE);
        login("auditor");
        voucherService.audit(v.getId());
        voucherService.post(v.getId());
        assertEquals(0, new BigDecimal("800").compareTo(balDr(FUT, "GT1001")));
        // 红冲 → REV 凭证落当期（会计实务：跨期冲原期间会被已关期间阻断），当期反向冲减
        FinVoucher rev = voucherService.reverse(v.getId(), "RWT 冲销测试", null);
        assertEquals("POSTED", rev.getStatus(), "REV 凭证即过账");
        String curPeriod = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        assertEquals(curPeriod, rev.getPeriod(), "红冲落当期");
        BigDecimal revCr = jdbc.query("SELECT COALESCE(CR_TOTAL,0) FROM erp_fin_balance "
                + "WHERE PERIOD=? AND ACCOUNT_CODE=? AND AUX_KEY=''", new Object[]{curPeriod, "GT1001"},
                (rs, i) -> rs.getBigDecimal(1)).stream().findFirst().orElse(BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("800").compareTo(revCr), "当期红冲反向冲减（贷 800）");
        assertEquals(0, new BigDecimal("800").compareTo(balDr(FUT, "GT1001")), "原期间余额留痕不变");
    }

    @Test
    void autoVoucherWritesBalanceUnchanged() {
        // 自动凭证（13 域调用面）：create 直过 + 同步写余额，行为不变
        FinVoucher v = voucherService.create("RWF", LocalDate.now(), "RWT 自动凭证",
                "RTEST_AUTO", "DOC-AUTO-1", null, List.of(
                        GlVoucherService.FinVoucherLineSpec.of("GT1001", "DR",
                                new BigDecimal("123"), "RWT"),
                        GlVoucherService.FinVoucherLineSpec.of("GT2202", "CR",
                                new BigDecimal("123"), "RWT")));
        assertEquals("POSTED", v.getStatus(), "自动凭证创建即 POSTED（行为不变）");
        String period = v.getPeriod();
        BigDecimal dr = jdbc.queryForObject("SELECT COALESCE(DR_TOTAL,0) FROM erp_fin_balance "
                + "WHERE PERIOD=? AND ACCOUNT_CODE=? AND AUX_KEY=''", BigDecimal.class, period, "GT1001");
        assertEquals(0, new BigDecimal("123").compareTo(dr), "自动凭证同步写余额");
    }

    @Test
    void auxDimensionalBalance() {
        // GT1122 标记 CUST：挂客户辅助 → 按客户维度分户余额
        FinVoucher h = new FinVoucher();
        h.setVoucherDate(LocalDate.now());
        h.setSummary("RWT 辅助");
        FinVoucherLine dr = new FinVoucherLine();
        dr.setAccountCode("GT1122");
        dr.setDirection("DR");
        dr.setAmount(new BigDecimal("1000"));
        dr.setAuxCustomer("CUST-A");
        FinVoucherLine cr = new FinVoucherLine();
        cr.setAccountCode("GT1001");
        cr.setDirection("CR");
        cr.setAmount(new BigDecimal("1000"));
        FinVoucher v = voucherService.createManual(h, List.of(dr, cr));
        login("auditor");
        voucherService.audit(v.getId());
        voucherService.post(v.getId());
        String period = v.getPeriod();
        // 按辅助键查余额
        String auxKey = balanceService.auxKey(null, null, "CUST-A", null, null, null, null);
        FinBalance bal = balanceDao.selectByKey(period, "GT1122", auxKey);
        assertNotNull(bal, "辅助维度余额行存在");
        assertEquals(0, new BigDecimal("1000").compareTo(bal.getDrTotal()));
        // 四栏查询含期初/期末
        List<Map<String, Object>> rows = balanceService.query(period, "GT1122", null);
        assertTrue(rows.stream().anyMatch(r -> auxKey.equals(String.valueOf(r.get("AUX_KEY")))),
                "分户余额在查询结果中");
        Map<String, Object> row = rows.stream()
                .filter(r -> auxKey.equals(String.valueOf(r.get("AUX_KEY")))).findFirst().orElseThrow();
        assertNotNull(row.get("openBalance"), "期初列");
        assertNotNull(row.get("closeBalance"), "期末列");
        // 期末 = 期初 + 借 − 贷 恒等
        BigDecimal open = (BigDecimal) row.get("openBalance");
        BigDecimal dr2 = (BigDecimal) row.get("DR_TOTAL");
        BigDecimal cr2 = (BigDecimal) row.get("CR_TOTAL");
        assertEquals(0, open.add(dr2).subtract(cr2).compareTo((BigDecimal) row.get("closeBalance")),
                "期末=期初+借−贷");
    }

    @Test
    void templateApplyMarksBlocked() {
        // 模板快照：GT1001 借 / GT2202 贷
        String rowsJson = "[{\"accountCode\":\"GT1001\",\"direction\":\"DR\",\"summary\":\"模板借\"},"
                + "{\"accountCode\":\"GT2202\",\"direction\":\"CR\",\"summary\":\"模板贷\"}]";
        FinVoucherTemplate t = new FinVoucherTemplate();
        t.setName("RWT-月末结转");
        t.setVoucherType("MANUAL");
        t.setRowsJson(rowsJson);
        FinVoucherTemplate saved = voucherService.saveTemplate(t);
        assertNotNull(saved.getId());
        Map<String, Object> applied = voucherService.applyTemplate(saved.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) applied.get("rows");
        assertEquals(2, rows.size(), "行快照一致");
        assertEquals("GT1001", rows.get(0).get("accountCode"));
        assertEquals(Boolean.FALSE, rows.get(0).get("blocked"), "启用科目不标注");
        // 停用科目行标注 blocked 不阻断
        accountService.setStatus("GT2202", "DISABLED");
        Map<String, Object> applied2 = voucherService.applyTemplate(saved.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows2 = (List<Map<String, Object>>) applied2.get("rows");
        assertEquals(Boolean.TRUE, rows2.get(1).get("blocked"), "停用科目行标注 blocked");
    }

    @Test
    void checklistPendingManualBlocksClose() {
        // 未来期间建 DRAFT 手工凭证 → ①未过账手工凭证失败 → 结账阻断
        mkManual(new BigDecimal("111"), FUT_DATE);
        Map<String, Object> check = periodService.checklist(FUT);
        assertEquals(Boolean.FALSE, check.get("allOk"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> pending = items.stream()
                .filter(i -> "pendingManual".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, pending.get("ok"), "存在未过账手工凭证");
        ServiceException ex = assertThrows(ServiceException.class, () -> periodService.close(FUT));
        assertTrue(ex.getMessage().contains("逐项列示"), "结账失败逐项列示");
    }

    // ---------- 结账第⑥项：未过账重估凭证（add-fx-revaluation task 5.2）----------

    @Test
    void checklistPendingRevalBlocksClose() {
        // 未来期间造 DRAFT 重估凭证 → ⑥失败 → 结账阻断
        jdbc.update("INSERT INTO erp_fin_voucher (ID,VOUCHER_NO,VOUCHER_TYPE,VOUCHER_DATE,SUMMARY,"
                + "PERIOD,SOURCE_TYPE,SOURCE_DOC_NO,STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-reval-1','REVAL-RWT-0001','REVAL',?,'RWT 重估凭证',?,'REVAL',?,'DRAFT','maker','0',0)",
                FUT_DATE, FUT, "REVAL-" + FUT);
        jdbc.update("INSERT INTO erp_fin_voucher_line (ID,VOUCHER_ID,LINE_NO,ACCOUNT_CODE,DIRECTION,"
                + "AMOUNT,SUMMARY,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-reval-l1','gt-reval-1',1,'GT1122','DR',100,'RWT 重估借方','maker','0',0),"
                + "('gt-reval-l2','gt-reval-1',2,'GT6601','CR',100,'RWT 重估贷方','maker','0',0)");

        Map<String, Object> check = periodService.checklist(FUT);
        assertEquals(Boolean.FALSE, check.get("allOk"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> reval = items.stream()
                .filter(i -> "pendingReval".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, reval.get("ok"), "存在未过账重估凭证");
        ServiceException ex = assertThrows(ServiceException.class, () -> periodService.close(FUT));
        assertTrue(ex.getMessage().contains("重估"), "结账阻断列示重估凭证：" + ex.getMessage());
    }

    @Test
    void checklistPostedRevalPasses() {
        // 重估凭证已 POSTED → ⑥通过（其余项配平后可结账）
        jdbc.update("INSERT INTO erp_fin_voucher (ID,VOUCHER_NO,VOUCHER_TYPE,VOUCHER_DATE,SUMMARY,"
                + "PERIOD,SOURCE_TYPE,SOURCE_DOC_NO,STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-reval-2','REVAL-RWT-0002','REVAL',?,'RWT 已过账重估',?,'REVAL',?,'POSTED','maker','0',0)",
                FUT_DATE, FUT, "REVAL-" + FUT);
        jdbc.update("INSERT INTO erp_fin_voucher_line (ID,VOUCHER_ID,LINE_NO,ACCOUNT_CODE,DIRECTION,"
                + "AMOUNT,SUMMARY,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-reval-l3','gt-reval-2',1,'GT1122','DR',100,'x','maker','0',0),"
                + "('gt-reval-l4','gt-reval-2',2,'GT6601','CR',100,'x','maker','0',0)");
        // POSTED 须同步写余额（试算平衡项才绿）
        jdbc.update("INSERT INTO erp_fin_balance (ID,PERIOD,ACCOUNT_CODE,AUX_KEY,DR_TOTAL,CR_TOTAL,"
                + "CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('gt-bal-reval-1',?,'GT1122','',100,0,'maker','0',0),"
                + "('gt-bal-reval-2',?,'GT6601','',0,100,'maker','0',0)", FUT, FUT);

        Map<String, Object> check = periodService.checklist(FUT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> reval = items.stream()
                .filter(i -> "pendingReval".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, reval.get("ok"), "已过账重估凭证放行");
    }

    @Test
    void checklistAllGreenClosesAndBlocksWrites() {
        // 配平资产勾稽：为未来期间插入调整行使 1601−1602 累计 = 卡片 NBV
        adjustAssetRecon(FUT);
        // 清掉可能存在的未过账手工凭证
        jdbc.update("UPDATE erp_fin_voucher SET DEL_FLAG='1' WHERE SOURCE_TYPE='MANUAL' AND PERIOD=?", FUT);
        Map<String, Object> check = periodService.checklist(FUT);
        assertEquals(Boolean.TRUE, check.get("allOk"), "四项全绿");
        Map<String, Object> closed = periodService.close(FUT);
        assertEquals("CLOSED", closed.get("status"));
        // 已关期间阻断：新手工凭证 + 新过账
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> mkManual(new BigDecimal("50"), FUT_DATE));
        assertTrue(ex1.getMessage().contains("已关闭"), "已关期间阻断新手工凭证");
        // 期间列表可见
        assertTrue(periodService.list().stream()
                .anyMatch(p -> FUT.equals(p.get("period")) && "CLOSED".equals(p.get("status"))));
    }

    /** 配平行：插 1601 余额行使累计(1601)−累计(1602) = 卡片 NBV（测试夹具，非业务逻辑） */
    private void adjustAssetRecon(String period) {
        BigDecimal cardNbv = jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(ASSET_COST - "
                + "ACCUM_DEPR - COALESCE(IMPAIRMENT,0), 0)),0) FROM erp_eam_asset_card "
                + "WHERE DEL_FLAG='0' AND STATUS NOT IN ('DISPOSED','REPLACED')", BigDecimal.class);
        BigDecimal cum1601 = jdbc.queryForObject("SELECT COALESCE(SUM(DR_TOTAL-CR_TOTAL),0) "
                + "FROM erp_fin_balance WHERE ACCOUNT_CODE='1601' AND DEL_FLAG='0'", BigDecimal.class);
        BigDecimal cum1602 = jdbc.queryForObject("SELECT COALESCE(SUM(DR_TOTAL-CR_TOTAL),0) "
                + "FROM erp_fin_balance WHERE ACCOUNT_CODE='1602' AND DEL_FLAG='0'", BigDecimal.class);
        BigDecimal delta = cardNbv.subtract(cum1601 == null ? BigDecimal.ZERO : cum1601)
                .add(cum1602 == null ? BigDecimal.ZERO : cum1602);
        jdbc.update("INSERT INTO erp_fin_balance (ID,PERIOD,ACCOUNT_CODE,AUX_KEY,DR_TOTAL,CR_TOTAL,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,?,?,?,?,'junit','0',0) ON DUPLICATE KEY UPDATE DR_TOTAL=VALUES(DR_TOTAL)",
                "adj-" + period, period, "1601", "",
                delta.max(BigDecimal.ZERO), delta.min(BigDecimal.ZERO).abs());
    }
}

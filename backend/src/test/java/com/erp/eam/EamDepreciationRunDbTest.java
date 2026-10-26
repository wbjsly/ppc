package com.erp.eam;

import com.erp.eam.DepreciationEngine;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDepreciationDetail;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.entity.mdm.MdmCostCenter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDepreciationDetailDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.dao.mdm.MdmCostCenterDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.SysParamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * add-asset-depreciation：月度计提/凭证/冲回 DbTest（spec asset-depreciation）。
 * 幂等卡控、应提清单、参数缺失、成本分摊凭证、台账回写、整单冲回。
 */
@SpringBootTest
class EamDepreciationRunDbTest {

    @Autowired
    private EamDepreciationService depreciationService;
    @Autowired
    private EamDepreciationRunDao runDao;
    @Autowired
    private EamDepreciationDetailDao detailDao;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private MdmCostCenterDao costCenterDao;
    @Autowired
    private FinVoucherDao voucherDao;
    @Autowired
    private FinVoucherLineDao voucherLineDao;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String PERIOD = "202609";

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
        // 保证勾稽参数存在（容差）
        if (sysParamService.getValue("TOLERANCE_DEFAULT") == null
                || sysParamService.getValue("TOLERANCE_DEFAULT").isBlank()) {
            jdbcInsertParam("TOLERANCE_DEFAULT", "0.01");
        }
        // 贷方科目参数
        if (sysParamService.getValue("EAM_DEPR_CREDIT") == null
                || sysParamService.getValue("EAM_DEPR_CREDIT").isBlank()) {
            jdbcInsertParam("EAM_DEPR_CREDIT", "1602");
        }
        if (sysParamService.getValue("EAM_DEPR_ACCT_DEFAULT") == null
                || sysParamService.getValue("EAM_DEPR_ACCT_DEFAULT").isBlank()) {
            jdbcInsertParam("EAM_DEPR_ACCT_DEFAULT", "6602");
        }
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='ASSET_IMPAIRMENT')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='ASSET_IMPAIRMENT'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID LIKE 'gl-%'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE ID LIKE 'gl-%'");
        jdbc.update("DELETE FROM erp_eam_asset_change WHERE CREATE_BY='junit'");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'ZC-TEST-%'");
        // 冒烟遗留明细（全表扫描断言会读到）
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'ZC2026%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%' "
                + "AND CREATE_BY='junit'");
        // 冒烟遗留计提单（幂等锚会阻断测试期间的计提）
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'SMK%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%' "
                + "AND CREATE_BY <> 'junit'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-TEST-%'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE ID='card-missing-param'");
        // 冒烟遗留卡自洽清理：应提清单是全局扫描，SMK 残留（含 ROU-xxx/购-xxx 派生卡）会污染断言
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE ASSET_NAME LIKE '%SMK%'");
        jdbc.update("DELETE FROM erp_mdm_cost_center WHERE CC_CODE LIKE 'CC-%-99'");
    }

    private void jdbcInsertParam(String key, String value) {
        jdbc.update("INSERT INTO erp_sys_param (PARAM_KEY, PARAM_VALUE, REMARK) VALUES (?, ?, ?)",
                key, value, "CQT 测试参数");
    }

    // ---- 造数 ----

    private MdmCostCenter mkCostCenter(String code, String type) {
        MdmCostCenter cc = new MdmCostCenter();
        cc.setCcCode(code);
        cc.setCcName("CQT-" + type + "中心");
        cc.setCostType(type);
        cc.setStatus("1");
        cc.setTreeLevel(1);
        cc.setParentId("");
        cc.setLegalEntityId("le-test");
        costCenterDao.insert(cc);
        return cc;
    }

    private EamAssetCard mkCard(String cardNo, String ccCode, BigDecimal cost,
                                Integer lifeYears, BigDecimal residualRate, String method,
                                LocalDate effectiveDate) {
        EamAssetCard c = new EamAssetCard();
        c.setCardNo(cardNo);
        c.setAssetName("CQT 测试资产-" + cardNo);
        c.setCategoryCode("MACH");
        c.setCostCenterCode(ccCode);
        c.setAssetCost(cost);
        c.setLifeYears(lifeYears);
        c.setResidualRate(residualRate);
        c.setDepreciationMethod(method);
        c.setEffectiveDate(effectiveDate);
        c.setStatus("IN_USE");
        c.setDeprStatus("NOT_DEPRECIATED");
        c.setAccumDepr(BigDecimal.ZERO);
        c.setImpairment(BigDecimal.ZERO);
        c.setLockFlag("1");
        cardDao.insert(c);
        return c;
    }

    private void setResidualValue(String cardNo, BigDecimal val) {
        EamAssetCard upd = new EamAssetCard();
        EamAssetCard old = findCard(cardNo);
        upd.setId(old.getId());
        upd.setResidualValue(val);
        cardDao.updateById(upd);
    }

    private EamAssetCard findCard(String cardNo) {
        return cardDao.selectList(null).stream()
                .filter(c -> cardNo.equals(c.getCardNo())).findFirst().orElseThrow();
    }

    // ---- 用例 ----

    @Test
    void runDepreciationPostsVoucherAndUpdatesLedger() {
        mkCostCenter("CC-PROD-99", "PROD");
        // 生产卡：原值 120 万，10 年，残值 5%，直线法，2026-01 启用 → 202609 起提月=202602，已提 8 月
        mkCard("ZC-TEST-0001", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0001", new BigDecimal("60000"));

        Map<String, Object> out = depreciationService.runDepreciation(PERIOD);
        assertNotNull(out.get("run"));
        assertNotNull(out.get("voucherId"), "应生成折旧凭证");
        assertEquals(1, out.get("cardCount"));

        EamDepreciationRun run = runDao.selectByPeriod(PERIOD);
        assertNotNull(run, "计提单已落库");
        assertEquals("POSTED", run.getStatus());
        assertNotNull(run.getVoucherId());

        // 直线法月折旧 = (1200000-60000)/120 = 9500
        BigDecimal expected = new BigDecimal("9500.00");
        assertEquals(0, expected.compareTo(run.getTotalDepr()), "合计折旧 = 9500");

        // 台账回写
        EamAssetCard card = findCard("ZC-TEST-0001");
        assertEquals(0, new BigDecimal("9500.00").compareTo(card.getAccumDepr()), "累计折旧回写");
        assertEquals("DEPRECIATING", card.getDeprStatus());

        // 明细锚凭证 + 借方科目 = 生产 510103
        List<EamDepreciationDetail> details = detailDao.selectList(null);
        assertEquals(1, details.size());
        EamDepreciationDetail d = details.get(0);
        assertEquals(run.getVoucherId(), d.getVoucherId(), "明细锚定凭证");
        assertEquals("5101", d.getDebitAccount(), "PROD 中心 → 制造费用-折旧(5101)");
    }

    @Test
    void voucherBalancedAndCostCenterAggregated() {
        // 两张卡：PROD(5101) + DEFAULT(6602)，验证借方按成本中心×科目聚合、借贷平、贷方 1602
        mkCostCenter("CC-PROD-99", "PROD");
        mkCard("ZC-TEST-0010", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0010", new BigDecimal("60000"));
        mkCard("ZC-TEST-0011", null, new BigDecimal("600000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0011", new BigDecimal("30000"));

        Map<String, Object> out = depreciationService.runDepreciation(PERIOD);
        EamDepreciationRun run = runDao.selectByPeriod(PERIOD);
        String vid = run.getVoucherId();

        // 借方按成本中心×科目聚合：5101 + 6602 两行，贷方 1602 一行
        List<FinVoucherLine> lines = voucherLineDao.selectList(
                new LambdaQueryWrapper<FinVoucherLine>().eq(FinVoucherLine::getVoucherId, vid));
        assertEquals(3, lines.size(), "借方两行(PROD/DEFAULT) + 贷方一行(1602)");
        BigDecimal dr = lines.stream().filter(l -> "DR".equals(l.getDirection()))
                .map(FinVoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = lines.stream().filter(l -> "CR".equals(l.getDirection()))
                .map(FinVoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, dr.compareTo(cr), "借贷平");
        assertEquals(0, dr.compareTo(run.getTotalDepr()), "借方合计 = 本期折旧");
        assertTrue(lines.stream().anyMatch(l -> "CR".equals(l.getDirection())
                && "1602".equals(l.getAccountCode())), "贷方科目 1602");
        // 成本中心聚合：PROD 卡 9500、DEFAULT 卡 4750
        assertTrue(lines.stream().anyMatch(l -> "DR".equals(l.getDirection())
                && "5101".equals(l.getAccountCode())
                && new BigDecimal("9500.00").compareTo(l.getAmount()) == 0), "PROD 借 9500");
        assertTrue(lines.stream().anyMatch(l -> "DR".equals(l.getDirection())
                && "6602".equals(l.getAccountCode())
                && new BigDecimal("4750.00").compareTo(l.getAmount()) == 0), "DEFAULT 借 4750");
    }

    @Test
    void detailVoucherBidirectionalLookup() {
        mkCostCenter("CC-PROD-99", "PROD");
        mkCard("ZC-TEST-0012", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0012", new BigDecimal("60000"));

        depreciationService.runDepreciation(PERIOD);
        EamDepreciationRun run = runDao.selectByPeriod(PERIOD);

        // 凭证 → 明细：按 voucherId 反查明细
        List<EamDepreciationDetail> byVoucher = detailDao.selectList(
                new LambdaQueryWrapper<EamDepreciationDetail>()
                        .eq(EamDepreciationDetail::getVoucherId, run.getVoucherId()));
        assertEquals(1, byVoucher.size(), "凭证→明细反查");

        // 明细 → 凭证：凭 sourceDocNo(=run单号) 反查凭证
        List<FinVoucher> bySource = voucherDao.selectList(
                new LambdaQueryWrapper<FinVoucher>()
                        .eq(FinVoucher::getSourceDocNo, run.getRunNo()));
        assertEquals(1, bySource.size(), "明细→凭证反查");
        assertEquals(run.getVoucherId(), bySource.get(0).getId(), "凭证 ID 一致");
    }

    @Test
    void samePeriodBlockedUntilReversed() {
        mkCostCenter("CC-PROD-99", "PROD");
        mkCard("ZC-TEST-0002", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0002", new BigDecimal("60000"));

        depreciationService.runDepreciation(PERIOD);
        // 同月重复 → 409
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> depreciationService.runDepreciation(PERIOD));
        assertTrue(ex.getMessage().contains("已计提"), "同月已计提应阻断：" + ex.getMessage());

        // 冲回后放行重提
        EamDepreciationRun run = runDao.selectByPeriod(PERIOD);
        depreciationService.reverse(run.getId(), "测试冲回");
        // 冲回后软删，selectByPeriod 返回 null
        assertNull(runDao.selectByPeriod(PERIOD), "冲回后 PERIOD_TAG 放行");
        // 重提成功
        Map<String, Object> out2 = depreciationService.runDepreciation(PERIOD);
        assertNotNull(out2.get("run"), "冲回后可重新计提");
    }

    @Test
    void missingParamsBlocksRun() {
        // 造一张原值为 0 的在用卡（FR-4.6-5-1：参数缺失必须阻断，而非静默按 0 算）
        jdbc.update("INSERT INTO erp_eam_asset_card (ID, CARD_NO, ASSET_NAME, CATEGORY_CODE, "
                + "ASSET_COST, LIFE_YEARS, RESIDUAL_RATE, DEPRECIATION_METHOD, EFFECTIVE_DATE, "
                + "STATUS, DEPR_STATUS, ACCUM_DEPR, IMPAIRMENT, LOCK_FLAG, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('card-missing-param', 'ZC-TEST-0006', 'CQT 缺参数卡', 'MACH', "
                + "0, 10, 0.05, 'STRAIGHT_LINE', '2026-01-15', 'IN_USE', 'NOT_DEPRECIATED', "
                + "0, 0, '1', 'junit', '0', 0)");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> depreciationService.runDepreciation(PERIOD));
        assertTrue(ex.getMessage().contains("参数缺失") || ex.getMessage().contains("补录"),
                "参数缺失应阻断：" + ex.getMessage());
    }

    @Test
    void notYetDepreciableExcludedFromList() {
        // 启用 2026-08-20 → 起提月 202609；对 202608 计提应无可提
        mkCard("ZC-TEST-0004", null, new BigDecimal("500000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 8, 20));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> depreciationService.runDepreciation("202608"));
        assertTrue(ex.getMessage().contains("无可计提"), "未到起提月应无可提：" + ex.getMessage());
    }

    // ================= 三方法引擎精确值（6.1）=================

    @Test
    void engineThreeMethodsExact() {
        BigDecimal cost = new BigDecimal("1200000");
        BigDecimal residual = new BigDecimal("60000");
        // SL：120万 原值、10 年 → (120万−6万)/120 = 9500
        var sl = DepreciationEngine.compute(cost, residual, 10, DepreciationEngine.METHOD_SL,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(0, new BigDecimal("9500.00").compareTo(sl.periodDepreciation()),
                "SL 月折旧 ¥9,500");
        // DDB：首月 = 120万 × 2 ÷ 10 ÷ 12 = 20000
        var ddb = DepreciationEngine.compute(cost, residual, 10, DepreciationEngine.METHOD_DDB,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(0, new BigDecimal("20000.00").compareTo(ddb.periodDepreciation()),
                "DDB 首月 ¥20,000");
        // SYD：首月 = (120万−6万) × 10 ÷ 55 ÷ 12 = 17272.73（年数总和 10×11/2=55）
        var syd = DepreciationEngine.compute(cost, residual, 10, DepreciationEngine.METHOD_SYD,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(0, new BigDecimal("17272.73").compareTo(syd.periodDepreciation()),
                "SYD 首月 ¥17,272.73");
    }

    @Test
    void reconDiffRecordedOnGlMismatch() {
        // 造一张同期间、同 sourceType=DEPRECIATION 的历史凭证，制造勾稽差异
        mkCostCenter("CC-PROD-99", "PROD");
        mkCard("ZC-TEST-0030", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), DepreciationEngine.METHOD_SL, LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0030", new BigDecimal("60000"));
        insertGlVoucher(PERIOD, "DEPRECIATION", "CQT-HIST", new BigDecimal("5000.00"));

        Map<String, Object> out = depreciationService.runDepreciation(PERIOD);
        EamDepreciationRun run = (EamDepreciationRun) out.get("run");
        // diff = |9500 − 5000| = 4500，记入 run 供审计（L4 差异预警）
        assertEquals(0, new BigDecimal("4500.00").compareTo(run.getReconDiff()),
                "勾稽差异 ¥4,500 已记录");
        // 计提仍完成（差异软记录不阻断）
        assertNotNull(run.getVoucherId(), "勾稽差异不阻断计提，凭证仍生成");
    }

    // ---- 造数 ----

    private void insertGlVoucher(String period, String sourceType, String sourceDocNo, BigDecimal amount) {
        jdbc.update("INSERT INTO erp_fin_voucher (ID, VOUCHER_NO, VOUCHER_TYPE, PERIOD, VOUCHER_DATE, "
                + "SUMMARY, SOURCE_TYPE, SOURCE_DOC_NO, STATUS, TOTAL_DR, TOTAL_CR, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'DEP', ?, ?, 'CQT 历史折旧凭证', ?, ?, 'POSTED', ?, ?, 'junit', '0', 0)",
                "gl-hist-" + sourceDocNo, "GLH" + sourceDocNo, period, periodEndDate(period),
                sourceType, sourceDocNo, amount, amount);
        jdbc.update("INSERT INTO erp_fin_voucher_line (ID, VOUCHER_ID, LINE_NO, ACCOUNT_CODE, DIRECTION, "
                + "AMOUNT, SUMMARY, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 1, ?, 'DR', ?, 'CQT 历史行', 'junit', '0', 0)",
                "gll-hist-" + sourceDocNo, "gl-hist-" + sourceDocNo, "6602", amount);
        jdbc.update("INSERT INTO erp_fin_voucher_line (ID, VOUCHER_ID, LINE_NO, ACCOUNT_CODE, DIRECTION, "
                + "AMOUNT, SUMMARY, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 2, '1602', 'CR', ?, 'CQT 历史行', 'junit', '0', 0)",
                "gll-hist-cr-" + sourceDocNo, "gl-hist-" + sourceDocNo, amount);
    }

    private java.time.LocalDate periodEndDate(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4));
        return java.time.YearMonth.of(y, m).atEndOfMonth();
    }

    @Test
    void reversalRollsBackLedgerAndMarksReversed() {
        mkCostCenter("CC-PROD-99", "PROD");
        mkCard("ZC-TEST-0005", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0005", new BigDecimal("60000"));

        depreciationService.runDepreciation(PERIOD);
        EamDepreciationRun run = runDao.selectByPeriod(PERIOD);
        String runId = run.getId();

        Map<String, Object> out = depreciationService.reverse(runId, "冲回测试");
        assertNotNull(out.get("reversedVoucherId"), "应生成红冲凭证");

        // 台账回滚：累计折旧归零
        EamAssetCard card = findCard("ZC-TEST-0005");
        assertEquals(0, BigDecimal.ZERO.compareTo(card.getAccumDepr()), "冲回后累计折旧回滚为 0");
        assertEquals("NOT_DEPRECIATED", card.getDeprStatus(), "冲回后折旧状态复位");

        // 明细标 REVERSED
        List<EamDepreciationDetail> details = detailDao.selectList(null);
        assertTrue(details.stream().allMatch(d -> "1".equals(d.getReversed())), "明细应标 REVERSED");

        // 计提单已软删（PERIOD_TAG 放行重提）
        assertNull(runDao.selectByPeriod(PERIOD), "冲回后 PERIOD_TAG 放行");

        // 4.3 重提成功（同月可再次计提）
        Map<String, Object> again = depreciationService.runDepreciation(PERIOD);
        assertNotNull(again.get("run"), "冲回后重提应成功");
        assertEquals(0, new BigDecimal("9500.00").compareTo(
                        (BigDecimal) again.get("totalDepreciation")), "重提折旧额一致");
    }

    // ================= 变动处理（4.1 政策变更 / 4.2 减值）=================

    @Test
    void policyChangeTakesEffectNextPeriod() {
        mkCostCenter("CC-PROD-99", "PROD");
        EamAssetCard card = mkCard("ZC-TEST-0020", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0020", new BigDecimal("60000"));

        // 提交政策变更：年限 10 → 20，生效期 = 基准期+1（审批通过后生效，不追溯本期）
        String effPeriod = nextPeriod(PERIOD);
        Map<String, Object> sub = depreciationService.changePolicy(
                card.getId(), 20, null, null, "测试政策变更", effPeriod);
        String changeId = (String) sub.get("changeId");
        assertNotNull(changeId, "变更单已创建");

        // 审批通过 → APPROVED 待生效
        Map<String, Object> appr = depreciationService.approveChange(changeId);
        assertEquals("APPROVED", appr.get("status"), "审批通过置 APPROVED");

        // 本期（202609）计提仍按旧政策 10 年 → 9500（不追溯）
        Map<String, Object> cur = depreciationService.runDepreciation(PERIOD);
        assertEquals(0, new BigDecimal("9500.00").compareTo(
                        (BigDecimal) cur.get("totalDepreciation")),
                "本期折旧仍按旧政策 ¥9,500");
        EamAssetCard after = findCard("ZC-TEST-0020");
        assertEquals(10, after.getLifeYears(), "本期不追溯，年限仍 10");

        // 下期（基准期+1）计提 → 应用新政策 20 年（120万−残值6万)/20年/12 = 4750
        String nextPeriod = nextPeriod(PERIOD);
        Map<String, Object> next = depreciationService.runDepreciation(nextPeriod);
        assertEquals(0, new BigDecimal("4750.00").compareTo(
                        (BigDecimal) next.get("totalDepreciation")),
                "下期按新政策 20 年计提 ¥4,750");
        assertEquals(1, next.get("policyApplied"), "下期应用 1 条政策变更");
        EamAssetCard applied = findCard("ZC-TEST-0020");
        assertEquals(20, applied.getLifeYears(), "政策生效后年限 = 20");
    }

    private static String nextPeriod(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4));
        m++;
        if (m > 12) { m = 1; y++; }
        return String.format("%d%02d", y, m);
    }

    @Test
    void impairmentRecomputesNextMonth() {
        mkCostCenter("CC-PROD-99", "PROD");
        EamAssetCard card = mkCard("ZC-TEST-0021", "CC-PROD-99", new BigDecimal("1200000"), 10,
                new BigDecimal("0.05"), "STRAIGHT_LINE", LocalDate.of(2026, 1, 15));
        setResidualValue("ZC-TEST-0021", new BigDecimal("60000"));

        // 造减值变更单（IMPAIRMENT，直接置 APPROVED 走审批通过）
        jdbc.update("INSERT INTO erp_eam_asset_change (ID, CHANGE_NO, CHANGE_TYPE, CARD_ID, CARD_NO, "
                + "IMPAIR_AMOUNT, STATUS, REASON, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('chg-impair-0021', 'BD-TEST-IMP-0021', 'IMPAIRMENT', ?, ?, 600000, "
                + "'PENDING_APPROVE', '测试减值', 'junit', '0', 0)",
                card.getId(), card.getCardNo());
        depreciationService.approveChange("chg-impair-0021");

        // 减值凭证 + 卡片 IMPAIRMENT 落值
        EamAssetCard impaired = findCard("ZC-TEST-0021");
        assertEquals(0, new BigDecimal("600000").compareTo(impaired.getImpairment()),
                "减值准备落值 60 万");
        List<FinVoucher> impVouchers = voucherDao.selectList(
                new LambdaQueryWrapper<FinVoucher>()
                        .eq(FinVoucher::getSourceType, "ASSET_IMPAIRMENT"));
        assertFalse(impVouchers.isEmpty(), "应生成减值凭证");

        // 次月（基准期+1）计提：减值后 NBV = 原值 − 累计折旧 − 减值60万（直线法月折旧额不变，NBV 下降）
        String nextP = nextPeriod(PERIOD);
        Map<String, Object> next = depreciationService.runDepreciation(nextP);
        assertNotNull(next.get("totalDepreciation"), "次月按减值后 NBV 重算计提成功");
        BigDecimal total = (BigDecimal) next.get("totalDepreciation");
        // 直线法月折旧额不变（减值不改月折旧，只降 NBV）
        assertEquals(0, new BigDecimal("9500.00").compareTo(total),
                "直线法月折旧额不变 ¥9,500");
        // 次月明细期初 NBV 应因减值 60 万而下降（对比未减值口径 120万−累计）
        List<EamDepreciationDetail> det = detailDao.selectList(
                new LambdaQueryWrapper<EamDepreciationDetail>()
                        .eq(EamDepreciationDetail::getPeriodTag, nextP));
        assertFalse(det.isEmpty(), "次月明细已生成");
        BigDecimal beginNbv = det.get(0).getBeginNbv();
        // 未减值口径 NBV = 120万 − 9500×8 = 1124000；减值 60 万后应 ≈ 524000
        assertTrue(beginNbv.compareTo(new BigDecimal("600000.00")) < 0,
                "期初 NBV 因减值 60 万显著下降：" + beginNbv);
    }
}

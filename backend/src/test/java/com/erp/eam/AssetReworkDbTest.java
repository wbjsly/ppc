package com.erp.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamCipCostDao;
import com.erp.dao.eam.EamCipProjectDao;
import com.erp.dao.eam.EamReworkItemDao;
import com.erp.dao.eam.EamReworkOrderDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamCipCost;
import com.erp.entity.eam.EamCipProject;
import com.erp.entity.eam.EamReworkItem;
import com.erp.entity.eam.EamReworkOrder;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.eam.EamCipService;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.eam.EamReworkService;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-rework：资产技改大修 DbTest（spec asset-rework + asset-depreciation delta）。
 * 前置断言逐项列示 / 已提足复核标记 / BR-37 三条件与下限 / BR-38 防拆分复核 /
 * 转在建凭证+科目缺失回滚 / CIP 1:1 互锚 / 验收须归集 / BR-40 重建卡 15.6 万 /
 * 转资凭证失败回滚 / 费用化凭证+回滚 / BR-39 停提与特批续提 / 次月起提不补提 / 冲回 / REBUILT 禁冲。
 */
@SpringBootTest
class AssetReworkDbTest {

    @Autowired
    private EamReworkService reworkService;
    @Autowired
    private EamDepreciationService depreciationService;
    @Autowired
    private EamCipService cipService;
    @Autowired
    private EamReworkOrderDao orderDao;
    @Autowired
    private EamReworkItemDao itemDao;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private EamCipProjectDao cipProjectDao;
    @Autowired
    private EamCipCostDao cipCostDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        login("junit");
        ensureParams();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('REWORK_TRANSFER','REWORK_REBUILD','REWORK_EXPENSE')) t1)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('REWORK_TRANSFER','REWORK_REBUILD','REWORK_EXPENSE')");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION'");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'ZC-RWK-%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('AssetRework','AssetReworkContinue')) t3)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('AssetRework','AssetReworkContinue')");
        jdbc.update("DELETE FROM erp_eam_rework_item WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_eam_rework_order) t4)");
        jdbc.update("DELETE FROM erp_eam_rework_order");
        // 1:1 CIP 项目（技改互锚）与归集
        jdbc.update("DELETE FROM erp_eam_cip_cost WHERE PROJECT_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_eam_cip_project WHERE REWORK_ORDER_ID IS NOT NULL) t5)");
        jdbc.update("DELETE FROM erp_eam_cip_project WHERE REWORK_ORDER_ID IS NOT NULL");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-RWK-%'");
        // 重建新卡（nextCardNo 生成 ZC+日期，锚 SOURCE_CAP_NO=原卡号）不删会污染全库扫描断言
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE SOURCE_CAP_NO LIKE 'ZC-RWK-%'");
        // 科目恢复（BR-33/凭证失败用例会临时置失效）
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN "
                + "('1601','1602','1604','2202','660201') AND DEL_FLAG='1'");
    }

    private void ensureParams() {
        jdbc.update("INSERT IGNORE INTO erp_sys_param (ID,PARAM_KEY,PARAM_VALUE,VALUE_TYPE,"
                + "PARAM_GROUP,REMARK,CREATE_BY) VALUES ('param-rwf-grace',"
                + "'CAPITALIZATION_GRACE_DAYS','30','NUMBER','EAM','junit 复位','junit')");
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='30' "
                + "WHERE PARAM_KEY='CAPITALIZATION_GRACE_DAYS'");
        // 分类资本化下限（MACH=5000 由 135 种子保证；防御性复位）
        jdbc.update("UPDATE erp_eam_asset_category SET CAPITALIZATION_FLOOR=5000 "
                + "WHERE CATEGORY_CODE='MACH'");
    }

    private void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, "ROLE_ADMIN"));
    }

    private void passAllNodes(String apprId) {
        for (int i = 0; i < 10; i++) {
            List<ApprovalTask> active = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                    .eq(ApprovalTask::getApprId, apprId)
                    .eq(ApprovalTask::getStatus, "ACTIVE"));
            if (active.isEmpty()) {
                return;
            }
            approvalEngine.pass(active.get(0).getId(), "同意");
        }
    }

    private EamAssetCard mkCard(String cardNo, BigDecimal cost, BigDecimal accum, LocalDate effective) {
        EamAssetCard c = new EamAssetCard();
        c.setCardNo(cardNo);
        c.setAssetName("RWK 测试资产-" + cardNo);
        c.setCategoryCode("MACH");
        c.setDeptName("RWK 甲部门");
        c.setCostCenterCode("CC-RWK-A");
        c.setAssetCost(cost);
        c.setLifeYears(10);
        c.setResidualRate(new BigDecimal("0.05"));
        c.setResidualValue(cost.multiply(new BigDecimal("0.05")));
        c.setDepreciationMethod("STRAIGHT_LINE");
        c.setEffectiveDate(effective);
        c.setStatus("IN_USE");
        c.setDeprStatus("NOT_DEPRECIATED");
        c.setAccumDepr(accum == null ? BigDecimal.ZERO : accum);
        c.setImpairment(BigDecimal.ZERO);
        c.setLockFlag("0");
        c.setPledgedFlag("0");
        c.setCountFreezeFlag("0");
        cardDao.insert(c);
        return c;
    }

    private EamReworkOrder mkOrder(EamAssetCard card, BigDecimal budget) {
        EamReworkOrder o = new EamReworkOrder();
        o.setCardId(card.getId());
        o.setContent("RWK 技改内容");
        o.setBudgetAmount(budget);
        o.setExpectedEffect("RWK 效果");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) reworkService.create(o,
                List.of(item("LIFT_CAPACITY", new BigDecimal("12"), "EV-RWK-001")));
        return (EamReworkOrder) out.get("order");
    }

    private EamReworkItem item(String type, BigDecimal quant, String evidence) {
        EamReworkItem it = new EamReworkItem();
        it.setCondType(type);
        it.setQuantValue(quant);
        it.setEvidenceNo(evidence);
        return it;
    }

    private String judgeCap(EamReworkOrder o) {
        reworkService.submit(o.getId());
        Map<String, Object> out = reworkService.judge(o.getId(), "CAP", "RWK 判定资本化",
                "EV-RWK-001", null);
        String apprId = out.get("apprId") == null ? null : String.valueOf(out.get("apprId"));
        if (apprId != null) {
            passAllNodes(apprId);   // 已提足/防拆分复核 → 过节点推进 JUDGED
        }
        return apprId;
    }

    private void addCost(String projectId, BigDecimal amount) {
        EamCipCost cost = new EamCipCost();
        cost.setProjectId(projectId);
        cost.setCostType("ENGINGEE");
        cost.setAmount(amount);
        cost.setSourceDocNo("INV-RWK-001");
        cipService.addCost(cost);
    }

    // ================= 用例 =================

    @Test
    void preConditionBlockersListed() {
        EamAssetCard frozen = mkCard("ZC-RWK-0001", new BigDecimal("50000"), null,
                LocalDate.of(2026, 1, 15));
        jdbc.update("UPDATE erp_eam_asset_card SET COUNT_FREEZE_FLAG='1' WHERE ID=?", frozen.getId());
        EamReworkOrder o = new EamReworkOrder();
        o.setCardId(frozen.getId());
        o.setContent("RWK");
        o.setBudgetAmount(new BigDecimal("8000"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reworkService.create(o, List.of(item("LIFT_CAPACITY", BigDecimal.TEN, "EV"))));
        assertTrue(ex.getMessage().contains("盘点冻结"), "前置阻断逐项列示盘点冻结");
    }

    @Test
    void fullyDepreciatedSetsReviewFlag() {
        // 原值 10 万 / 累计 9.5 万（残值 5%）→ 已提足
        EamAssetCard full = mkCard("ZC-RWK-0002", new BigDecimal("100000"),
                new BigDecimal("95000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(full, new BigDecimal("20000"));
        assertEquals("1", orderDao.selectById(o.getId()).getFloorReviewFlag(), "已提足置复核标记");
    }

    @Test
    void br37RequiresQualifyingCondition() {
        EamAssetCard card = mkCard("ZC-RWK-0003", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("20000"));
        reworkService.submit(o.getId());
        // 量化不足（寿命 0.5 年 < 1）
        ServiceException ex = assertThrows(ServiceException.class, () -> reworkService.judge(
                o.getId(), "CAP", "不足", null,
                List.of(item("LIFT_LIFE", new BigDecimal("0.5"), "EV"))));
        assertTrue(ex.getMessage().contains("BR-37"), "BR-37 依据不足阻断");
    }

    @Test
    void br37QualifyingCapJudgedDirectly() {
        EamAssetCard card = mkCard("ZC-RWK-0004", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("20000"));
        String apprId = judgeCap(o);
        EamReworkOrder after = orderDao.selectById(o.getId());
        assertEquals("JUDGED", after.getReworkStatus(), "量化达标直接判定 JUDGED");
        assertEquals("CAP", after.getCapJudge());
        assertNull(apprId, "无复核标记不走审批");
    }

    @Test
    void capBelowFloorBlockedUnlessMerge() {
        EamAssetCard card = mkCard("ZC-RWK-0005", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        // 预算 3000 < 下限 5000，无同卡历史 → 合并不达标 → CAP 阻断
        EamReworkOrder o = mkOrder(card, new BigDecimal("3000"));
        reworkService.submit(o.getId());
        ServiceException ex = assertThrows(ServiceException.class, () ->
                reworkService.judge(o.getId(), "CAP", "低于下限", null, null));
        assertTrue(ex.getMessage().contains("下限"), "低于分类下限阻断资本化");
        // 判费用化放行
        Map<String, Object> out = reworkService.judge(o.getId(), "EXPENSE", "转费用化", null, null);
        assertEquals("JUDGED", out.get("status"));
    }

    @Test
    void br38MergeForcesFinanceReview() {
        EamAssetCard card = mkCard("ZC-RWK-0006", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        // 历史单（近 12 月）：3000；本单 3000 → 合计 6000 ≥ 5000 → 合并强制复核
        EamReworkOrder prev = mkOrder(card, new BigDecimal("3000"));
        reworkService.submit(prev.getId());
        reworkService.judge(prev.getId(), "EXPENSE", "历史费用化", null, null);
        reworkService.expense(prev.getId());
        reworkService.close(prev.getId());   // 结案释放同卡在途位（CLOSED 仍计入 12 月笔数）
        EamReworkOrder cur = mkOrder(card, new BigDecimal("3000"));
        reworkService.submit(cur.getId());
        EamReworkOrder curAfter = orderDao.selectById(cur.getId());
        assertEquals("1", curAfter.getFloorMergeFlag(), "BR-38 合并计算命中");
        Map<String, Object> out = reworkService.judge(cur.getId(), "CAP", "合并达标资本化", null, null);
        assertEquals(Boolean.TRUE, out.get("reviewRequired"), "命中强制财务经理复核");
        String apprId = String.valueOf(out.get("apprId"));
        passAllNodes(apprId);
        assertEquals("JUDGED", orderDao.selectById(cur.getId()).getReworkStatus(), "复核通过推进 JUDGED");
    }

    @Test
    void toInReworkVoucherAndCipProject() {
        EamAssetCard card = mkCard("ZC-RWK-0007", new BigDecimal("120000"),
                new BigDecimal("40000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("50000"));
        judgeCap(o);
        Map<String, Object> out = reworkService.toInRework(o.getId());
        // NBV = 120000-40000 = 80000
        assertEquals(0, new BigDecimal("80000.00").compareTo(
                new BigDecimal(String.valueOf(out.get("origNbv")))), "转入时 NBV 快照");
        EamReworkOrder after = orderDao.selectById(o.getId());
        assertEquals("IN_REWORK", after.getReworkStatus());
        assertEquals("IN_REWORK", cardDao.selectById(card.getId()).getStatus(), "卡片置在建-技改");
        EamCipProject p = cipProjectDao.selectById(after.getCipProjectId());
        assertNotNull(p, "1:1 CIP 项目已建");
        assertEquals(o.getId(), p.getReworkOrderId(), "项目反向互锚");
        // 转移凭证：DR 1604 = 80000
        BigDecimal dr1604 = jdbc.queryForObject("SELECT COALESCE(SUM(l.AMOUNT),0) FROM "
                + "erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='REWORK_TRANSFER' AND v.SOURCE_DOC_NO=? "
                + "AND l.DIRECTION='DR' AND l.ACCOUNT_CODE='1604'", BigDecimal.class, o.getRwfNo());
        assertEquals(0, new BigDecimal("80000.00").compareTo(dr1604), "段1 转在建借 1604=NBV");
    }

    @Test
    void toInReworkMissingAccountRollback() {
        EamAssetCard card = mkCard("ZC-RWK-0008", new BigDecimal("120000"),
                new BigDecimal("40000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("50000"));
        judgeCap(o);
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='1604'");
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> reworkService.toInRework(o.getId()));
            assertTrue(ex.getMessage().contains("1604"), "科目缺失列编码（BR-33 式）");
            assertEquals("IN_USE", cardDao.selectById(card.getId()).getStatus(), "失败卡片不变");
            assertEquals("JUDGED", orderDao.selectById(o.getId()).getReworkStatus());
        } finally {
            jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='1604'");
        }
    }

    @Test
    void inReworkStopsDepreciationAndContinueResumes() {
        // 常驻在用卡：保证批次非空（引擎无卡可提会抛 422）
        mkCard("ZC-RWK-9998", new BigDecimal("60000"), null,
                YearMonth.now().minusMonths(2).atDay(1));
        LocalDate eff = YearMonth.now().minusMonths(1).atDay(15);
        EamAssetCard card = mkCard("ZC-RWK-0009", new BigDecimal("120000"), null, eff);
        EamReworkOrder o = mkOrder(card, new BigDecimal("50000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        String cur = YearMonth.now().toString().replace("-", "");
        // 停提：当期明细无该卡
        depreciationService.runDepreciation(cur);
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail "
                + "WHERE CARD_NO='ZC-RWK-0009' AND RUN_ID IN "
                + "(SELECT ID FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%')", Integer.class);
        assertEquals(0, cnt, "BR-39 停提卡排除出当期应提清单");
        // 特批续提：审批通过 → 下一期继续计提
        Map<String, Object> cont = reworkService.requestContinueDepr(o.getId(), "RWK 设备继续运行");
        passAllNodes(String.valueOf(cont.get("apprId")));
        assertEquals("1", orderDao.selectById(o.getId()).getContinueDeprFlag(), "特批续提标记写入");
        String next = YearMonth.now().plusMonths(1).toString().replace("-", "");
        depreciationService.runDepreciation(next);
        Integer cnt2 = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail "
                + "WHERE CARD_NO='ZC-RWK-0009' AND RUN_ID IN "
                + "(SELECT ID FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%')", Integer.class);
        assertEquals(1, cnt2, "BR-39 特批续提期间继续计提");
    }

    @Test
    void acceptRequiresCostsThenCompletes() {
        EamAssetCard card = mkCard("ZC-RWK-0010", new BigDecimal("120000"),
                new BigDecimal("40000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("50000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        String pid = orderDao.selectById(o.getId()).getCipProjectId();
        // 无归集不可验收（CIP complete 前置）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reworkService.accept(o.getId(), LocalDate.now()));
        assertTrue(ex.getMessage().contains("归集"), "无归集不可验收");
        addCost(pid, new BigDecimal("96000"));
        Map<String, Object> out = reworkService.accept(o.getId(), LocalDate.now());
        assertEquals("COMPLETED", out.get("status"));
        assertNotNull(orderDao.selectById(o.getId()).getAcceptDate(), "验收日锚写入（C-4.13-06 起算）");
    }

    @Test
    void rebuildRebuildsCardBr40() {
        // S-4.13-18 锚：原值 120 万 / 累计 114 万 → NBV 6 万；归集 9.6 万 → 新原值 15.6 万
        EamAssetCard card = mkCard("ZC-RWK-0011", new BigDecimal("1200000"),
                new BigDecimal("1140000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("96000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        String pid = orderDao.selectById(o.getId()).getCipProjectId();
        addCost(pid, new BigDecimal("96000"));
        LocalDate accept = LocalDate.now();
        reworkService.accept(o.getId(), accept);
        Map<String, Object> out = reworkService.rebuild(o.getId(), 5);
        assertEquals(0, new BigDecimal("156000.00").compareTo(
                new BigDecimal(String.valueOf(out.get("newCost")))), "BR-40 新原值 = 6 万 + 9.6 万");
        EamAssetCard old = cardDao.selectById(card.getId());
        assertEquals("REPLACED", old.getStatus(), "原卡归档保留历史");
        EamAssetCard neu = jdbc.queryForObject("SELECT * FROM erp_eam_asset_card WHERE CARD_NO=?",
                (rs, i) -> {
                    EamAssetCard c = new EamAssetCard();
                    c.setCardNo(rs.getString("CARD_NO"));
                    c.setAssetCost(rs.getBigDecimal("ASSET_COST"));
                    c.setLifeYears(rs.getInt("LIFE_YEARS"));
                    c.setSourceCapNo(rs.getString("SOURCE_CAP_NO"));
                    c.setStatus(rs.getString("STATUS"));
                    c.setEffectiveDate(rs.getDate("EFFECTIVE_DATE").toLocalDate());
                    return c;
                }, String.valueOf(out.get("newCardNo")));
        assertEquals(0, new BigDecimal("156000").compareTo(neu.getAssetCost()), "新卡原值");
        assertEquals(card.getCardNo(), neu.getSourceCapNo(), "新卡锚原卡号");
        assertEquals(5, neu.getLifeYears(), "年限=技术评估值");
        assertEquals("IN_USE", neu.getStatus());
        assertEquals(accept, neu.getEffectiveDate(), "生效日=验收日（次月起提锚）");
        // 转资凭证 DR 1601 = 156000
        BigDecimal dr1601 = jdbc.queryForObject("SELECT COALESCE(SUM(l.AMOUNT),0) FROM "
                + "erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='REWORK_REBUILD' AND v.SOURCE_DOC_NO=? "
                + "AND l.DIRECTION='DR' AND l.ACCOUNT_CODE='1601'", BigDecimal.class, o.getRwfNo());
        assertEquals(0, new BigDecimal("156000.00").compareTo(dr1601), "转资凭证借 1601=新原值");
    }

    @Test
    void rebuildVoucherFailureRollback() {
        EamAssetCard card = mkCard("ZC-RWK-0012", new BigDecimal("1200000"),
                new BigDecimal("1140000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("96000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        addCost(orderDao.selectById(o.getId()).getCipProjectId(), new BigDecimal("96000"));
        reworkService.accept(o.getId(), LocalDate.now());
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='1601'");
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> reworkService.rebuild(o.getId(), 5));
            assertTrue(ex.getMessage().contains("1601"), "转资科目缺失阻断");
            assertEquals("COMPLETED", orderDao.selectById(o.getId()).getReworkStatus(), "验收不推进");
            assertEquals("IN_REWORK", cardDao.selectById(card.getId()).getStatus(), "原卡仍为在建态未归档");
        } finally {
            jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='1601'");
        }
    }

    @Test
    void expenseVoucherAndRollback() {
        EamAssetCard card = mkCard("ZC-RWK-0013", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("20000"));
        reworkService.submit(o.getId());
        reworkService.judge(o.getId(), "EXPENSE", "不资本化", null, null);
        Map<String, Object> out = reworkService.expense(o.getId());
        assertEquals("EXPENSED", out.get("status"));
        BigDecimal drExp = jdbc.queryForObject("SELECT COALESCE(SUM(l.AMOUNT),0) FROM "
                + "erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='REWORK_EXPENSE' AND v.SOURCE_DOC_NO=? "
                + "AND l.DIRECTION='DR' AND l.ACCOUNT_CODE='660201'", BigDecimal.class, o.getRwfNo());
        assertEquals(0, new BigDecimal("20000.00").compareTo(drExp), "费用凭证借 660201=预算");
        // 失败回滚：科目缺失
        EamAssetCard card2 = mkCard("ZC-RWK-0014", new BigDecimal("80000"), null,
                LocalDate.of(2026, 1, 15));
        EamReworkOrder o2 = mkOrder(card2, new BigDecimal("15000"));
        reworkService.submit(o2.getId());
        reworkService.judge(o2.getId(), "EXPENSE", "不资本化", null, null);
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='660201'");
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> reworkService.expense(o2.getId()));
            assertTrue(ex.getMessage().contains("660201"), "费用科目缺失阻断");
            assertEquals("JUDGED", orderDao.selectById(o2.getId()).getReworkStatus(), "判定不推进");
        } finally {
            jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='660201'");
        }
    }

    @Test
    void reverseRestoresCardAndProject() {
        EamAssetCard card = mkCard("ZC-RWK-0015", new BigDecimal("120000"),
                new BigDecimal("40000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("50000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        Map<String, Object> out = reworkService.reverse(o.getId());
        assertEquals(1, out.get("reversedVouchers"), "转移凭证已冲回");
        assertEquals("IN_USE", cardDao.selectById(card.getId()).getStatus(), "卡片恢复在用");
        assertEquals("JUDGED", orderDao.selectById(o.getId()).getReworkStatus(), "退回已判定");
        String pid = orderDao.selectById(o.getId()).getCipProjectId();
        assertEquals("VOID", cipProjectDao.selectById(pid).getStatus(), "空项目作废");
        // 已归集不可冲回
        reworkService.toInRework(o.getId());
        addCost(orderDao.selectById(o.getId()).getCipProjectId(), new BigDecimal("1000"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reworkService.reverse(o.getId()));
        assertTrue(ex.getMessage().contains("归集"), "已归集阻断冲回");
    }

    @Test
    void rebuiltCannotReverse() {
        EamAssetCard card = mkCard("ZC-RWK-0016", new BigDecimal("1200000"),
                new BigDecimal("1140000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("96000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        addCost(orderDao.selectById(o.getId()).getCipProjectId(), new BigDecimal("96000"));
        reworkService.accept(o.getId(), LocalDate.now());
        reworkService.rebuild(o.getId(), 5);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reworkService.reverse(o.getId()));
        assertTrue(ex.getMessage().contains("处置"), "REBUILT 后禁冲（引导走处置）");
    }

    @Test
    void newCardStartsNextMonthNoCatchup() {
        // 验收日=上月 15 日：上月期无明细（不补提）、本月期有明细（次月起提）
        // 常驻卡：保证上期批次非空（引擎无卡可提会抛 422）
        mkCard("ZC-RWK-9999", new BigDecimal("60000"), null,
                YearMonth.now().minusMonths(2).atDay(1));
        EamAssetCard card = mkCard("ZC-RWK-0017", new BigDecimal("120000"),
                new BigDecimal("40000"), LocalDate.of(2026, 1, 15));
        EamReworkOrder o = mkOrder(card, new BigDecimal("96000"));
        judgeCap(o);
        reworkService.toInRework(o.getId());
        addCost(orderDao.selectById(o.getId()).getCipProjectId(), new BigDecimal("96000"));
        LocalDate accept = YearMonth.now().minusMonths(1).atDay(15);
        reworkService.accept(o.getId(), accept);
        Map<String, Object> out = reworkService.rebuild(o.getId(), 5);
        String newNo = String.valueOf(out.get("newCardNo"));
        String prevPeriod = YearMonth.now().minusMonths(1).toString().replace("-", "");
        String curPeriod = YearMonth.now().toString().replace("-", "");
        depreciationService.runDepreciation(prevPeriod);
        depreciationService.runDepreciation(curPeriod);
        Integer prevCnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail "
                + "WHERE CARD_NO=? AND RUN_ID IN (SELECT ID FROM erp_eam_depreciation_run "
                + "WHERE PERIOD_TAG=?)", Integer.class, newNo, prevPeriod);
        Integer curCnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail "
                + "WHERE CARD_NO=? AND RUN_ID IN (SELECT ID FROM erp_eam_depreciation_run "
                + "WHERE PERIOD_TAG=?)", Integer.class, newNo, curPeriod);
        assertEquals(0, prevCnt, "BR-40 停提/验收当月不补提");
        assertEquals(1, curCnt, "次月按新政策起提");
    }
}

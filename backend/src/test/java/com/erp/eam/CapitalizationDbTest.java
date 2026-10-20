package com.erp.eam;

import com.erp.entity.eam.EamCapitalization;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.eam.EamCapitalizationService;
import com.erp.service.eam.EamCipService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add-asset-capitalization：转资核心（spec asset-capitalization）。
 * 双模式全链、一物一卡拆分与上限、BR-07/08/09/10 四规则、折旧预览精确值、
 * 凭证生成与失败回滚、超期分级。
 */
@SpringBootTest
class CapitalizationDbTest {

    @Autowired
    private EamCapitalizationService capService;
    @Autowired
    private EamCipService cipService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE SOURCE_CAP_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_eam_capitalization WHERE ASSET_NAME LIKE 'CQT%') t1)");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='CAPITAL') t2)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='CAPITAL'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='AssetCapital') t3)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='AssetCapital'");
        jdbc.update("DELETE FROM erp_eam_capitalization WHERE ASSET_NAME LIKE 'CQT%'");
        jdbc.update("DELETE FROM erp_eam_cip_cost WHERE PROJECT_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_eam_cip_project WHERE NAME LIKE 'CQT%') t4)");
        jdbc.update("DELETE FROM erp_eam_cip_project WHERE NAME LIKE 'CQT%'");
        // 采购侧造数（PO/GR/GR 行），避免重跑主键冲突
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE GR_ID IN " +
                "('cqt-gr-1','cqt-gr-2')");
        jdbc.update("DELETE FROM erp_proc_gr WHERE ID IN ('cqt-gr-1','cqt-gr-2')");
        jdbc.update("DELETE FROM erp_proc_po WHERE ID IN ('cqt-po-1','cqt-po-2')");
    }

    /** 造 CIP 项目 + 归集（默认全额结算，BR-08 差额为 0 走全额转资）+ 完工 */
    private String mkCip(String name, String collected) {
        var p = cipService.createProject(mkProj(name));
        var cost = new com.erp.entity.eam.EamCipCost();
        cost.setProjectId(p.getId());
        cost.setCostType("ENGINGEE");
        cost.setAmount(new BigDecimal(collected));
        cipService.addCost(cost);
        // 全额结算（差额 0）；需测 BR-08 部分转资的用例再单独 UPDATE 降 SETTLED_AMOUNT
        jdbc.update("UPDATE erp_eam_cip_project SET SETTLED_AMOUNT=? WHERE ID=?",
                new BigDecimal(collected), p.getId());
        cipService.complete(p.getId(), null);
        return p.getId();
    }

    private com.erp.entity.eam.EamCipProject mkProj(String name) {
        var p = new com.erp.entity.eam.EamCipProject();
        p.setName(name);
        return p;
    }

    private EamCapitalization mkCipCap(String projectId, String qty, String total) {
        EamCapitalization c = new EamCapitalization();
        c.setMode("CIP");
        c.setSourceId(projectId);
        c.setAssetName("CQT 在建转资设备");
        c.setCategoryCode("MACH");
        c.setQty(Integer.parseInt(qty));
        c.setTotalAmount(new BigDecimal(total));
        return c;
    }

    // ================= CIP 模式全链 =================

    @Test
    void cipFullChainBuildsCardsAndVoucher() {
        String pid = mkCip("CQT 全链项目", "500000");
        EamCapitalization cap = mkCipCap(pid, "2", "500000");
        @SuppressWarnings("unchecked")
        Map<String, Object> created = capService.create(cap);
        EamCapitalization c = (EamCapitalization) created.get("cap");
        assertEquals("DRAFT", c.getStatus());
        assertEquals(new BigDecimal("250000.00"), c.getUnitAmount(), "单件原值 = 合计/数量");

        c = capService.submit(c.getId());
        assertEquals("PENDING_APPROVE", c.getStatus(), "提交后待审批");

        // 审批通过 → 建卡 + 凭证
        Map<String, Object> done = capService.completeFromApproval(c.getId());
        @SuppressWarnings("unchecked")
        List<?> cards = (List<?>) done.get("cards");
        assertEquals(2, cards.size(), "一物一卡拆 2 张");

        String fin = jdbc.queryForObject(
                "SELECT STATUS FROM erp_eam_capitalization WHERE ID=?", String.class, c.getId());
        assertEquals("COMPLETED", fin, "审核通过置 COMPLETED");

        Long cardCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_asset_card WHERE SOURCE_CAP_ID=?", Long.class, c.getId());
        assertEquals(2L, cardCnt, "落库 2 张卡");
        String lock = jdbc.queryForObject(
                "SELECT LOCK_FLAG FROM erp_eam_asset_card WHERE SOURCE_CAP_ID=? LIMIT 1",
                String.class, c.getId());
        assertEquals("1", lock, "凭证过账后卡片锁定（BR-4.13-10）");

        // 凭证：借 1601 / 贷 1604
        String voucherId = jdbc.queryForObject(
                "SELECT VOUCHER_ID FROM erp_eam_capitalization WHERE ID=?", String.class, c.getId());
        assertNotNull(voucherId, "凭证 ID 回写");
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line WHERE VOUCHER_ID=?",
                voucherId);
        assertEquals(2, lines.size(), "凭证两行");
        assertTrue(lines.stream().anyMatch(l -> "1601".equals(l.get("ACCOUNT_CODE"))
                && "DR".equals(l.get("DIRECTION"))), "借方固定资产 1601");
        assertTrue(lines.stream().anyMatch(l -> "1604".equals(l.get("ACCOUNT_CODE"))
                && "CR".equals(l.get("DIRECTION"))), "贷方在建工程 1604");
    }

    @Test
    void duplicateSourceBlocked() {
        String pid = mkCip("CQT 重复转资", "100000");
        EamCapitalization c1 = (EamCapitalization) capService.create(mkCipCap(pid, "1", "100000"))
                .get("cap");
        assertNotNull(c1.getId());
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.create(mkCipCap(pid, "1", "100000")));
        assertEquals(422, ex.getCode(), "已转资来源重复发起 422");
    }

    // ================= BR-4.13-09 资本化下限 =================

    @Test
    void belowCapitalizationFloorBlocked() {
        String pid = mkCip("CQT 低于下限", "3000");
        EamCapitalization c = mkCipCap(pid, "1", "3000");
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.create(c));
        assertTrue(ex.getMessage().contains("资本化下限"), "BR-09 阻断文案：" + ex.getMessage());
        assertEquals(422, ex.getCode());
    }

    // ================= BR-4.13-08 部分转资 =================

    @Test
    void cipPartialRequiredWhenDiffExists() {
        String pid = mkCip("CQT 部分转资", "100000");
        // 已结算 9 万 → 差额 1 万，未标部分转资 → 422
        jdbc.update("UPDATE erp_eam_cip_project SET SETTLED_AMOUNT=90000 WHERE ID=?", pid);
        EamCapitalization c = mkCipCap(pid, "1", "100000");
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.create(c));
        assertEquals(422, ex.getCode(), "BR-08 未选部分转资 422");
        assertTrue(ex.getMessage().contains("部分转资"), "提示部分转资");

        // 标部分转资 + 说明 → 通过
        c.setPartialFlag("1");
        c.setPartialRemark("设备款未结 1 万，先转已结算部分");
        EamCapitalization ok = (EamCapitalization) capService.create(c).get("cap");
        assertEquals("1", ok.getPartialFlag());
    }

    // ================= BR-4.13-07 原值一致性 =================

    @Test
    void purchaseOriginalValueMismatchBlocked() {
        // 造 PO（含税 113 万，税率 0.13 → 不含税 100 万）+ 已过账 GR + 质检合格行
        jdbc.update("INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, " +
                "SUPPLIER_NAME, STATUS, TOTAL_AMT, TAX_RATE, CREATE_BY, DEL_FLAG, VER_NO) " +
                "VALUES ('cqt-po-1','CQT-PO-001','NORMAL','MANUAL','sup-cqt','CQT 供应商'," +
                "'APPROVED',1130000,0.13,'junit','0',0)");
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, PO_ID, PO_NO, SUPPLIER_ID, " +
                "SUPPLIER_NAME, STATUS, ARRIVAL_DATE, CREATE_BY, DEL_FLAG, VER_NO) " +
                "VALUES ('cqt-gr-1','CQT-GR-001','PO','cqt-po-1','CQT-PO-001','sup-cqt'," +
                "'CQT 供应商','POSTED','2026-10-08','junit','0',0)");
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, " +
                "RECEIVED_QTY, QC_STATUS, STATUS, VER_NO) " +
                "VALUES ('cqt-grl-1','cqt-gr-1',1,'SMKI001','CQT 设备',1,'RELEASED','POSTED',0)");

        EamCapitalization c = new EamCapitalization();
        c.setMode("PURCHASE");
        c.setSourceId("cqt-po-1");
        c.setAssetName("CQT 采购设备");
        c.setCategoryCode("MACH");
        c.setQty(1);
        c.setTotalAmount(new BigDecimal("1020000")); // 偏离 100 万 → 超容差
        c.setEffectiveDate(LocalDate.of(2026, 10, 8));
        EamCapitalization created = (EamCapitalization) capService.create(c).get("cap");
        assertEquals(LocalDate.of(2026, 10, 8), created.getEffectiveDate(),
                "启用日期=验收合格日（BR-06）");

        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.submit(created.getId()));
        assertEquals(422, ex.getCode(), "BR-07 原值超差阻断");
        assertTrue(ex.getMessage().contains("差异超容差"), "文案含差异超容差");

        // 调回一致 → 提交通过
        jdbc.update("UPDATE erp_eam_capitalization SET TOTAL_AMOUNT=1000000, UNIT_AMOUNT=1000000 " +
                "WHERE ID=?", created.getId());
        EamCapitalization reloaded = jdbc.queryForObject(
                "SELECT * FROM erp_eam_capitalization WHERE ID=?",
                (rs, i) -> {
                    EamCapitalization e = new EamCapitalization();
                    e.setId(rs.getString("ID"));
                    e.setVerNo(rs.getInt("VER_NO"));
                    return e;
                }, created.getId());
        EamCapitalization submitted = capService.submit(reloaded.getId());
        assertEquals("PENDING_APPROVE", submitted.getStatus(), "容差内可提交");
    }

    // ================= 一物一卡上限 =================

    @Test
    void cardQtyLimit500() {
        String pid = mkCip("CQT 超上限", "600000");
        EamCapitalization c = mkCipCap(pid, "600", "600000");
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.create(c));
        assertEquals(422, ex.getCode(), "600 件超拆分上限 422");
        assertTrue(ex.getMessage().contains("拆单"), "提示拆单");
    }

    // ================= 折旧预览精确值 =================

    @Test
    void depreciationPreviewExact() {
        String pid = mkCip("CQT 折旧预览", "1000000");
        EamCapitalization c = mkCipCap(pid, "1", "1000000");
        c.setLifeYears(10);
        c.setResidualRate(new BigDecimal("0.05"));
        EamCapitalization created = (EamCapitalization) capService.create(c).get("cap");
        Map<String, Object> p = capService.depreciationPreview(created.getId());
        assertEquals(0, new BigDecimal("50000.00").compareTo((BigDecimal) p.get("residual")),
                "残值 5 万");
        assertEquals(0, new BigDecimal("7916.67").compareTo(
                        (BigDecimal) p.get("monthlyDepreciation")),
                "月折旧 ¥7,916.67（(100万−5万)/10/12）");
    }

    // ================= 驳回回草稿 =================

    @Test
    void rejectToDraft() {
        String pid = mkCip("CQT 驳回", "200000");
        EamCapitalization c = (EamCapitalization) capService.create(mkCipCap(pid, "1", "200000"))
                .get("cap");
        c = capService.submit(c.getId());
        EamCapitalization back = capService.rejectToDraft(c.getId(), "成本中心归属有误");
        assertEquals("DRAFT", back.getStatus(), "驳回回草稿");
        assertEquals("成本中心归属有误", back.getRejectReason());
    }

    // ================= BR-4.13-10 锁定后字段不可改 =================

    @Test
    void lockedCardPolicyImmutable() {
        String pid = mkCip("CQT 锁定", "300000");
        EamCapitalization c = (EamCapitalization) capService.create(mkCipCap(pid, "1", "300000"))
                .get("cap");
        capService.completeFromApproval(c.getId());
        var card = jdbc.queryForMap(
                "SELECT * FROM erp_eam_asset_card WHERE SOURCE_CAP_ID=?", c.getId());
        String cardId = (String) card.get("ID");

        // 锁定后改折旧年限 → BR-4.13-10 拦截 422
        var locked = new com.erp.entity.eam.EamAssetCard();
        locked.setId(cardId);
        locked.setLifeYears(99);
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> capService.updateCard(locked));
        assertEquals(422, ex.getCode(), "锁定后改折旧政策 422");
        assertTrue(ex.getMessage().contains("资产变更流程"), "提示走资产变更流程");

        // 非锁定字段（存放地点）放行
        var loc = new com.erp.entity.eam.EamAssetCard();
        loc.setId(cardId);
        loc.setStorageLocation("B-07 新厂房");
        assertEquals("B-07 新厂房",
                capService.updateCard(loc).getStorageLocation(), "非锁定字段可改");

        // 折旧年限快照不变
        String life = jdbc.queryForObject(
                "SELECT LIFE_YEARS FROM erp_eam_asset_card WHERE ID=?", String.class, cardId);
        assertEquals("10", life, "折旧年限快照不变");
    }

    // ================= 超期分级 =================

    @Test
    void pendingListOverdueLevels() {
        // CIP 完工 70 天前 → 升级；入库验收 45 天前 → 预警
        String pid = mkCip("CQT 超期项目", "100000");
        jdbc.update("UPDATE erp_eam_cip_project SET COMPLETION_DATE=? WHERE ID=?",
                LocalDate.now().minusDays(70), pid);
        jdbc.update("INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, " +
                "SUPPLIER_NAME, STATUS, TOTAL_AMT, TAX_RATE, CREATE_BY, DEL_FLAG, VER_NO) " +
                "VALUES ('cqt-po-2','CQT-PO-002','NORMAL','MANUAL','sup-cqt2','CQT 超期供应商'," +
                "'APPROVED',500000,0.13,'junit','0',0)");
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, PO_ID, PO_NO, SUPPLIER_ID, " +
                "SUPPLIER_NAME, STATUS, ARRIVAL_DATE, CREATE_BY, DEL_FLAG, VER_NO) " +
                "VALUES ('cqt-gr-2','CQT-GR-002','PO','cqt-po-2','CQT-PO-002','sup-cqt2'," +
                "'CQT 超期供应商','POSTED',?, 'junit','0',0)", LocalDate.now().minusDays(45));
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, " +
                "RECEIVED_QTY, QC_STATUS, STATUS, VER_NO) " +
                "VALUES ('cqt-grl-2','cqt-gr-2',1,'SMKI001','CQT 超期设备',1,'RELEASED','POSTED',0)");

        List<Map<String, Object>> list = capService.pendingList();
        assertTrue(list.stream().anyMatch(r -> "CQT-PO-002".equals(r.get("sourceDocNo"))
                        && "WARNED".equals(r.get("level"))),
                "45 天 → 预警：" + list);
        assertTrue(list.stream().anyMatch(r -> "CQT-PO-002".equals(r.get("sourceDocNo"))
                        && Long.valueOf(45).equals(r.get("overdueDays"))),
                "超期天数 45");
    }
}

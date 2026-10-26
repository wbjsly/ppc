package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.OsMaterial;
import com.erp.entity.mrp.OsMaterialLine;
import com.erp.service.mrp.OsMaterialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外协甲供收货定额冲减与余料退回 DB 集成（task 5.2，spec outsourcing-material-supply
 * 「收货定额冲减与余料退回」）：定额冲减（BOM 单耗×(1+损耗)×收货数，封顶行余量）、
 * 同 GR 幂等、余料退回入库（在途 → 工厂可用）、超退 422、全退 RETURNED、红冲恢复在途与幂等。
 */
@SpringBootTest
class OsMaterialConsumeDbTest {

    private static final String WH = "WH-OSC";
    private static final String ITEM = "IT-OSC-01";
    private static final String WC = "WC-OSC-T";
    private static final String SUP = "SUP-OSC-01";
    private static final String DP = "DP-OSC-CO-0001";
    private static final String PO = "PO-OSC-0001";
    private static final String MO = "MO-OSC-0001";
    private static final String MO_ID = "mo-osc-1";
    private static final String BATCH = "OSC-B1";

    @Autowired
    private OsMaterialService osmService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        // 工单（计划量 100）
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, PRODUCT_CODE, QTY, STATUS, CREATE_BY, DEL_FLAG) "
                + "VALUES (?, ?, 'PRD-OSC', 100, 'RELEASED', 'junit', '0')", MO_ID, MO);
        // BOM 快照：单耗 2 × (1+损耗 0.05) = 2.1/件
        jdbc.update("INSERT INTO erp_mrp_mo_bom (ID, MO_ID, LINE_NO, TREE_LEVEL, OWNER_ITEM, ITEM_CODE, "
                + "ITEM_NAME, UNIT_QTY, LOSS_RATE, BASE_UNIT, CREATE_BY, DEL_FLAG) VALUES "
                + "('bom-osc-1', ?, 1, 1, 'PRD-OSC', ?, '外协甲供物料', 2.000000, 0.0500, 'KG', "
                + "'junit', '0')", MO_ID, ITEM);
        // 物料 / 供应商 / 外协 WC
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('it-osc-01', ?, '外协甲供物料', '0001', 'KG', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit', '0')", ITEM);
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, STATUS, CREATE_BY, "
                + "DEL_FLAG) VALUES ('sup-osc-01', ?, '冲减测试供应商', 'ACTIVE', 'junit', '0')", SUP);
        jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, SUPPLIER_CODE, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('wc-osc-t', ?, '冲减外协中心', 'OUTSOURCED', ?, "
                + "'1', 'junit', '0')", WC, SUP);
        // 甲供外协派工单（带回联 PO）
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, "
                + "WC_CODE, WC_NAME, WC_TYPE, OUTSOURCE_FLAG, OS_SUPPLY_TYPE, PO_NO, STATUS, CREATE_BY) "
                + "VALUES ('dp-osc-1', ?, ?, ?, 10, '机加工', ?, ?, 'OUTSOURCED', '1', 'COMPANY_SUPPLY', "
                + "?, 'RELEASED', 'junit')", DP, MO_ID, MO, WC, "冲减外协中心", PO);
        // 库存 200（批次 OSC-B1）
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG) VALUES ('st-osc-1', ?, ?, '外协甲供物料', "
                + "?, 200, 200, 'junit', '0')", WH, ITEM, BATCH);
        // 批次台账（BR-4.1-08：批次管理物料入库须可解析有效期；退回沿用原效期）
        jdbc.update("INSERT INTO erp_inv_batch (ID, ITEM_CODE, BATCH_NO, EXPIRY_DATE, STATUS, "
                + "CREATE_BY, DEL_FLAG) VALUES ('ib-osc-1', ?, ?, '2027-12-31', '1', 'junit', '0')",
                ITEM, BATCH);
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 1 WHERE TYPE_CODE IN "
                + "('OS_MAT_OUT','OS_MAT_IN')");
        BomTestAuth.login("junit", "ROLE_PLANNER", "ROLE_PURCHASER", "ROLE_ADMIN");
    }

    @AfterEach
    void cleanup() {
        BomTestAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_mrp_os_consume WHERE DISPATCH_ID LIKE 'dp-osc%' "
                + "OR ANCHOR_NO LIKE 'GR-OSC%' OR ANCHOR_NO LIKE 'RET-OSC%'");
        jdbc.update("DELETE l FROM erp_mrp_os_material_line l "
                + "INNER JOIN erp_mrp_os_material h ON l.OSM_ID = h.ID "
                + "WHERE h.DISPATCH_NO LIKE 'DP-OSC%'");
        jdbc.update("DELETE FROM erp_mrp_os_material WHERE DISPATCH_NO LIKE 'DP-OSC%'");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DP-OSC%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID = ?", MO_ID);
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID = ?", MO_ID);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE = ?", WC);
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE SUPPLIER_CODE = ?", SUP);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
    }

    /** 创建 + 出库过账一张发料单（issuedQty 指定），返回头 */
    private OsMaterial postedIssue(String issuedQty) {
        OsMaterial h = osmService.create(Map.of(
                "dispatchId", dispatchId(),
                "lines", List.of(Map.of(
                        "itemCode", ITEM,
                        "itemName", "外协甲供物料",
                        "batchNo", BATCH,
                        "warehouseCode", WH,
                        "unit", "KG",
                        "reqQty", issuedQty))));
        return osmService.post(h.getId());
    }

    private String dispatchId() {
        return jdbc.queryForObject("SELECT ID FROM erp_mrp_dispatch WHERE DISPATCH_NO = ?",
                String.class, DP);
    }

    private OsMaterialLine onlyLine(String osmId) {
        return jdbc.queryForObject("SELECT ID FROM erp_mrp_os_material_line WHERE OSM_ID = ?",
                (rs, i) -> {
                    OsMaterialLine l = new OsMaterialLine();
                    l.setId(rs.getString(1));
                    return l;
                }, osmId);
    }

    private BigDecimal pool(String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? "
                + "AND ITEM_CODE = ? AND BATCH_NO = ?", BigDecimal.class, WH, ITEM, BATCH);
    }

    // ---------- 定额冲减 ----------

    @Test
    void consumeByBomQuota() {
        OsMaterial h = postedIssue("50"); // AVAIL 200→150，OS 入池 50，QTY 守恒 200
        assertEquals(0, new BigDecimal("50").compareTo(pool("OS_QTY")), "发料出库在途入池 50");
        assertEquals(0, new BigDecimal("150").compareTo(pool("AVAILABLE_QTY")), "可用转在途");
        assertEquals(0, new BigDecimal("200").compareTo(pool("QTY")), "总量守恒");

        // 收货 10 件 → 定额 = 2.1 × 10 = 21
        assertTrue(osmService.consumeOnReceipt(dispatchId(), "GR-OSC-001", new BigDecimal("10")));
        OsMaterialLine l = jdbc.queryForObject(
                "SELECT ID, CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = ?",
                (rs, i) -> {
                    OsMaterialLine x = new OsMaterialLine();
                    x.setId(rs.getString(1));
                    x.setConsumedQty(rs.getBigDecimal(2));
                    return x;
                }, h.getId());
        assertEquals(0, new BigDecimal("21").compareTo(l.getConsumedQty()), "定额 2.1×10=21");
        assertEquals(0, new BigDecimal("29").compareTo(pool("OS_QTY")), "在途 50−21=29");
        assertEquals(0, new BigDecimal("179").compareTo(pool("QTY")), "总量随消耗减");
        // 台账关联 GR
        Map<String, Object> led = jdbc.queryForMap(
                "SELECT CONSUME_TYPE, ANCHOR_NO, QTY FROM erp_mrp_os_consume WHERE ANCHOR_NO = 'GR-OSC-001'");
        assertEquals("CONSUME", led.get("CONSUME_TYPE"));
        assertEquals(0, new BigDecimal("21").compareTo((BigDecimal) led.get("QTY")), "台账数量");
    }

    @Test
    void consumeIdempotentPerGr() {
        postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-002", new BigDecimal("10"));
        assertFalse(osmService.consumeOnReceipt(dispatchId(), "GR-OSC-002", new BigDecimal("10")),
                "同 GR 二次过账幂等 false");
        assertEquals(0, new BigDecimal("29").compareTo(pool("OS_QTY")), "不重复冲减");
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_os_consume "
                + "WHERE ANCHOR_NO = 'GR-OSC-002'", Integer.class);
        assertEquals(1, cnt, "台账仅一条");
    }

    @Test
    void consumeCappedByLineRemaining() {
        postedIssue("50");
        // 收货 100 件 → 定额 210，封顶行余量 50
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-003", new BigDecimal("100"));
        assertEquals(0, BigDecimal.ZERO.compareTo(pool("OS_QTY")), "在途冲尽不为负");
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = (SELECT ID "
                        + "FROM erp_mrp_os_material WHERE DISPATCH_NO = ?)", BigDecimal.class, DP);
        assertEquals(0, new BigDecimal("50").compareTo(consumed), "封顶行余量 50");
    }

    // ---------- 余料退回 ----------

    @Test
    void listBackfillsRemainQty() {
        OsMaterial h = postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-005", new BigDecimal("10")); // 消耗 21
        List<OsMaterial> heads = osmService.list(DP, null, null);
        assertEquals(1, heads.size());
        OsMaterial hit = heads.stream().filter(x -> x.getId().equals(h.getId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("29").compareTo(hit.getRemainQty()),
                "列表待退回余量 = 实发 50 − 冲减 21");
    }

    @Test
    void returnRemainingRestoresAvailable() {
        OsMaterial h = postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-010", new BigDecimal("10")); // 消耗 21，余 29
        OsMaterialLine l = onlyLine(h.getId());

        // 退回 10：在途 29→19，可用 150→160，总量守恒 179
        OsMaterial after = osmService.returnRemaining(h.getId(),
                List.of(Map.of("lineId", l.getId(), "qty", "10")));
        assertEquals(OsMaterial.ST_POSTED, after.getStatus(), "仍有余量保持 POSTED");
        assertEquals(0, new BigDecimal("19").compareTo(pool("OS_QTY")), "在途减 10");
        assertEquals(0, new BigDecimal("160").compareTo(pool("AVAILABLE_QTY")), "退回入可用");
        assertEquals(0, new BigDecimal("179").compareTo(pool("QTY")), "在途→可用平移守恒");
        // 退库过账流水
        Integer tx = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction "
                + "WHERE BIZ_DOC_NO = ? AND TYPE_CODE = 'OS_MAT_IN'", Integer.class, h.getOsmNo());
        assertEquals(1, tx, "生成 OS_MAT_IN 流水");
        // 退回留痕
        BigDecimal returned = jdbc.queryForObject(
                "SELECT RETURNED_QTY FROM erp_mrp_os_material_line WHERE ID = ?",
                BigDecimal.class, l.getId());
        assertEquals(0, new BigDecimal("10").compareTo(returned), "行退回累计 10");
    }

    @Test
    void returnOverRemainBlocksWithGap() {
        OsMaterial h = postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-011", new BigDecimal("10")); // 余 29
        OsMaterialLine l = onlyLine(h.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> osmService.returnRemaining(h.getId(),
                        List.of(Map.of("lineId", l.getId(), "qty", "40"))));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("不足"), "提示可退余量不足：" + e.getMessage());
        // 在途未被误减
        assertEquals(0, new BigDecimal("29").compareTo(pool("OS_QTY")), "超退被拒后在途不变");
    }

    @Test
    void fullReturnMarksReturned() {
        OsMaterial h = postedIssue("30");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-012", new BigDecimal("10")); // 消耗 21，余 9
        OsMaterialLine l = onlyLine(h.getId());
        OsMaterial after = osmService.returnRemaining(h.getId(),
                List.of(Map.of("lineId", l.getId(), "qty", "9")));
        assertEquals(OsMaterial.ST_RETURNED, after.getStatus(), "全退终态 RETURNED");
        assertNotNull(after.getReturnDate(), "退回完成时间留痕");
    }

    // ---------- 红冲恢复 ----------

    @Test
    void reverseRestoresInTransit() {
        OsMaterial h = postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-020", new BigDecimal("10")); // 消耗 21

        // 红冲 10 件 → 恢复 2.1×10 = 21（封顶净冲减 21）
        assertTrue(osmService.reverseConsume(PO, "RET-OSC-001", new BigDecimal("10")));
        assertEquals(0, new BigDecimal("50").compareTo(pool("OS_QTY")), "在途恢复 29→50");
        assertEquals(0, new BigDecimal("200").compareTo(pool("QTY")), "总量恢复");
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = ?", BigDecimal.class, h.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(consumed), "行净冲减归零");
        Map<String, Object> led = jdbc.queryForMap(
                "SELECT CONSUME_TYPE, QTY FROM erp_mrp_os_consume WHERE ANCHOR_NO = 'RET-OSC-001'");
        assertEquals("REVERSE", led.get("CONSUME_TYPE"));
        // 幂等：同退货单二次恢复 false
        assertFalse(osmService.reverseConsume(PO, "RET-OSC-001", new BigDecimal("10")));
        assertEquals(0, new BigDecimal("50").compareTo(pool("OS_QTY")), "不重复恢复");
    }

    @Test
    void reversePartialAndCapByNetConsumed() {
        OsMaterial h = postedIssue("50");
        osmService.consumeOnReceipt(dispatchId(), "GR-OSC-021", new BigDecimal("10")); // 消耗 21
        // 部分红冲 4 件 → 恢复 8.4
        osmService.reverseConsume(PO, "RET-OSC-002", new BigDecimal("4"));
        assertEquals(0, new BigDecimal("37.4").compareTo(pool("OS_QTY")), "29+8.4=37.4");
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = ?", BigDecimal.class, h.getId());
        assertEquals(0, new BigDecimal("12.6").compareTo(consumed), "21−8.4=12.6");
        // 大额红冲封顶净冲减：恢复 12.6 至 0，不为负
        osmService.reverseConsume(PO, "RET-OSC-003", new BigDecimal("100"));
        assertEquals(0, new BigDecimal("50").compareTo(pool("OS_QTY")), "封顶恢复到发料全量");
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                "SELECT CONSUMED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = ?",
                BigDecimal.class, h.getId())), "净冲减钳 0");
    }

    @Test
    void reverseNonOutsourceSilent() {
        postedIssue("50");
        assertFalse(osmService.reverseConsume("PO-NONE", "RET-OSC-009", new BigDecimal("1")),
                "非外协 PO 静默 false");
        assertFalse(osmService.consumeOnReceipt("dp-none", "GR-OSC-009", new BigDecimal("1")),
                "无派工单静默 false");
    }
}

package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.OsMaterial;
import com.erp.entity.mrp.OsMaterialLine;
import com.erp.service.mrp.DispatchService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外协发料单 DB 集成（真实 MySQL，task 5.1，spec outsourcing-material-supply
 * 「外协发料单创建与出库」）：甲供创建出库、供应商自购 422、可用库存不足逐行缺口、
 * 过账扣减工厂可用库存。
 */
@SpringBootTest
class OsMaterialDbTest {

    private static final String WH = "WH-OST";
    private static final String ITEM = "IT-OST-01";
    private static final String WC = "WC-OST-T";
    private static final String SUP = "SUP-OST-01";
    private static final String DP_COMPANY = "DP-OST-CO-0001";
    private static final String DP_SUPPLIER = "DP-OST-SB-0001";
    private static final String MO = "MO-OST-0001";

    @Autowired
    private OsMaterialService osmService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        // 物料（批次管理）
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, "
                + "BATCH_FLAG, SERIAL_FLAG, STATUS, CREATE_BY, DEL_FLAG) VALUES "
                + "('it-ost-01', ?, '外协甲供物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', "
                + "'1', '0', '1', 'junit', '0')", ITEM);
        // 供应商
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, STATUS, CREATE_BY, "
                + "DEL_FLAG) VALUES ('sup-ost-01', ?, '外协测试供应商', 'ACTIVE', 'junit', '0')", SUP);
        // 外协工作中心（绑定供应商）
        jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, SUPPLIER_CODE, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('wc-ost-t', ?, '外协测试中心', 'OUTSOURCED', ?, "
                + "'1', 'junit', '0')", WC, SUP);
        // 两张外协派工单：甲供 / 供应商自购
        insertDispatch(DP_COMPANY, "dp-ost-co", "COMPANY_SUPPLY");
        insertDispatch(DP_SUPPLIER, "dp-ost-sb", "SUPPLIER_BUY");
        // 库存：批次 OST-B1 可用 50
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "QTY, AVAILABLE_QTY, CREATE_BY) VALUES ('st-ost-1', ?, ?, '外协甲供物料', "
                + "'OST-B1', 50, 50, 'junit')", WH, ITEM);
        // 文档类型启用（迁移 126 种子）
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 1 WHERE TYPE_CODE IN ('OS_MAT_OUT','OS_MAT_IN')");
        BomTestAuth.login("junit", "ROLE_PLANNER", "ROLE_PURCHASER", "ROLE_ADMIN");
    }

    @AfterEach
    void cleanup() {
        BomTestAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE l FROM erp_mrp_os_material_line l "
                + "INNER JOIN erp_mrp_os_material h ON l.OSM_ID = h.ID "
                + "WHERE h.DISPATCH_NO LIKE 'DP-OST-%'");
        jdbc.update("DELETE FROM erp_mrp_os_material WHERE DISPATCH_NO LIKE 'DP-OST-%'");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DP-OST-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE = ?", WC);
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE MO_NO = ?", MO);
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE SUPPLIER_CODE = ?", SUP);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_ops_outbox WHERE EVENT_TYPE = 'STOCK.MOVED' AND SOURCE LIKE 'OSM%'");
    }

    private void insertDispatch(String no, String id, String supplyType) {
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, "
                + "WC_CODE, WC_NAME, WC_TYPE, OUTSOURCE_FLAG, OS_SUPPLY_TYPE, STATUS, CREATE_BY) "
                + "VALUES (?, ?, ?, ?, 10, '机加工', ?, ?, 'OUTSOURCED', '1', ?, 'RELEASED', 'junit')",
                id, no, "mo-" + id, MO, WC, "外协测试中心", supplyType);
    }

    private String dispatchId(String no) {
        return jdbc.queryForObject("SELECT ID FROM erp_mrp_dispatch WHERE DISPATCH_NO = ?",
                String.class, no);
    }

    private Map<String, Object> body(String dispatchId, String reqQty) {
        return Map.of(
                "dispatchId", dispatchId,
                "lines", List.of(Map.of(
                        "itemCode", ITEM,
                        "itemName", "外协甲供物料",
                        "batchNo", "OST-B1",
                        "warehouseCode", WH,
                        "unit", "PCS",
                        "reqQty", reqQty)));
    }

    // ---------- 甲供创建 + 出库过账 ----------

    @Test
    void companySupplyCreateAndPost() {
        OsMaterial h = osmService.create(body(dispatchId(DP_COMPANY), "30"));
        assertNotNull(h.getOsmNo());
        assertTrue(h.getOsmNo().startsWith("OSM"), "单号前缀 OSM");
        assertEquals(OsMaterial.ST_DRAFT, h.getStatus());
        assertEquals(SUP, h.getSupplierCode(), "供应商随外协 WC 解析");
        assertNotNull(h.getSupplierName());
        assertEquals(MO, h.getMoNo());
        assertEquals(10, h.getOpSeq());

        // 详情回显行
        Map<String, Object> d = osmService.detail(h.getId());
        @SuppressWarnings("unchecked")
        List<OsMaterialLine> lines = (List<OsMaterialLine>) d.get("lines");
        assertEquals(1, lines.size());
        assertEquals(0, new BigDecimal("30").compareTo(lines.get(0).getReqQty()));

        // 出库过账：DRAFT → POSTED，可用 50 → 20
        OsMaterial posted = osmService.post(h.getId());
        assertEquals(OsMaterial.ST_POSTED, posted.getStatus());
        assertNotNull(posted.getPostDate());
        BigDecimal avail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? "
                        + "AND BATCH_NO = 'OST-B1'", BigDecimal.class, WH, ITEM);
        assertEquals(0, new BigDecimal("20").compareTo(avail), "过账后可用 20");
        // 实发回写
        BigDecimal issued = jdbc.queryForObject(
                "SELECT ISSUED_QTY FROM erp_mrp_os_material_line WHERE OSM_ID = ?", BigDecimal.class, h.getId());
        assertEquals(0, new BigDecimal("30").compareTo(issued), "实发 30");
        // 过账流水
        Integer tx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_NO = ? AND TYPE_CODE = 'OS_MAT_OUT'",
                Integer.class, posted.getOsmNo());
        assertEquals(1, tx, "生成 1 条 OS_MAT_OUT 流水");
    }

    // ---------- 供应商自购 422 ----------

    @Test
    void supplierBuyBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> osmService.create(body(dispatchId(DP_SUPPLIER), "10")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("COMPANY_SUPPLY")
                        || e.getMessage().contains("甲供") || e.getMessage().contains("SUPPLIER_BUY"),
                "提示仅甲供可发料：" + e.getMessage());
    }

    // ---------- 可用库存不足逐行缺口 ----------

    @Test
    void insufficientStockBlocksWithShortfall() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> osmService.create(body(dispatchId(DP_COMPANY), "80")));
        assertEquals(422, e.getCode());
        // 可用 50 < 请领 80，消息含缺口
        assertTrue(e.getMessage().contains("不足"), "提示库存不足：" + e.getMessage());
        assertTrue(e.getMessage().contains(ITEM), "列出缺口物料：" + e.getMessage());
        // 库存未被动过（创建不预扣）
        BigDecimal avail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ?",
                BigDecimal.class, WH, ITEM);
        assertEquals(0, new BigDecimal("50").compareTo(avail), "创建阶段不动库存");
    }

    // ---------- 已过账不可再过账 ----------

    @Test
    void postedCannotPostAgain() {
        OsMaterial h = osmService.create(body(dispatchId(DP_COMPANY), "10"));
        osmService.post(h.getId());
        ServiceException e = assertThrows(ServiceException.class, () -> osmService.post(h.getId()));
        assertEquals(422, e.getCode());
        // 已过账不可删除
        ServiceException e2 = assertThrows(ServiceException.class, () -> osmService.delete(h.getId()));
        assertEquals(422, e2.getCode());
    }

    // ---------- 列表筛选 ----------

    @Test
    void listFiltersByDispatchAndStatus() {
        osmService.create(body(dispatchId(DP_COMPANY), "10"));
        List<OsMaterial> all = osmService.list(DP_COMPANY, null, null);
        assertEquals(1, all.size(), "按派工单号筛选");
        assertEquals(1, osmService.list(null, SUP, null).size(), "按供应商筛选");
        assertEquals(1, osmService.list(null, null, OsMaterial.ST_DRAFT).size(), "按状态筛选");
        assertEquals(0, osmService.list("DP-NONE", null, null).size(), "无匹配空集");
    }

    // ---------- 权限：质检员 403 ----------

    @Test
    void qualityRoleForbidden() {
        BomTestAuth.logout();
        BomTestAuth.login("qc", "ROLE_INSPECTOR");
        ServiceException e = assertThrows(ServiceException.class,
                () -> osmService.create(body(dispatchId(DP_COMPANY), "10")));
        assertEquals(403, e.getCode());
    }
}

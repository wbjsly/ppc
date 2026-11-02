package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotScanWorkService;
import com.erp.service.inv.PickTaskService;
import com.erp.entity.inv.PickTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 扫码作业五场景库测（add-iot-mobile 13.2，FR-4.11-2-x）：
 * 各 1 正 1 反——RFID 损坏拒收/重复防重、货位条件阻断、批次不一致冻结货位、
 * 发货复核数量阻断、盘亏 L2 提示与 >10% 复盘阻断。
 */
@SpringBootTest
class ScanWorkDbTest {

    @Autowired
    private IotScanWorkService service;
    @Autowired
    private PickTaskService pickTaskService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-scn-";
    private String itemCode;
    private String supplierId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_SHOP_FLOOR"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        purge();
        seedCommon();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_mobile_todo WHERE TITLE LIKE '%批次不一致%' OR REF_ID LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_serial WHERE SERIAL_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_putaway WHERE SOURCE_DOC_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_pick_scan_log WHERE TASK_ID IN (SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE SRC_DOC_NO LIKE ? OR ITEM_CODE LIKE ?", P + "%", P + "%");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='CountDiff'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='CountDiff'");
        jdbc.update("DELETE FROM erp_inv_count_line WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_count_task WHERE TASK_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN (SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN (SELECT ID FROM erp_inv_material_issue WHERE ISSUE_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE SHIP_ID IN (SELECT ID FROM erp_sd_shipment WHERE SHIP_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE SHIP_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE GR_ID IN (SELECT ID FROM erp_proc_gr WHERE GR_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_proc_gr WHERE GR_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_bin WHERE BIN_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_zone WHERE ZONE_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE SUPPLIER_CODE LIKE ?", P + "%");
    }

    private void seedCommon() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        itemCode = P + "IT-" + n;
        supplierId = P + "SUP-" + n;
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, BLACKLIST_RESULT, " +
                        "STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,'PASS','ACTIVE',?,NOW(),'0',0)",
                supplierId, supplierId, "扫码测试供应商", "junit");
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, " +
                        "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, TEMP_LEVEL, BATCH_FLAG, " +
                        "STANDARD_COST, STATUS, CREATE_BY) VALUES (?,?,?,'0001','PC','STRUCT','BUY'," +
                        "'NORMAL','CHILLED','1',10,'1','junit')",
                P + "IT-ID-" + n, itemCode, "扫码测试冷藏物料");
    }

    // ---------- 13.2.1 收货扫码 ----------

    @Test
    void receiveDamagedRfidHardBlocks() {
        Map<String, Object> payload = receivePayload("DAMAGE-SER-001");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.receive(payload));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("L1"));
        assertTrue(ex.getMessage().contains("损坏"));
    }

    @Test
    void receiveSuccessThenDuplicateBlocked() {
        Map<String, Object> ok = service.receive(receivePayload(P + "SN-" + UUID.randomUUID()
                .toString().substring(0, 8)));
        assertNotNull(ok.get("grNo"), "正常收货生成收货单");
        assertEquals("OK", ok.get("level"));
        // 同序列号再扫 → 防重 409
        String serial = (String) ok.get("serialNo");
        Map<String, Object> again = receivePayload(serial);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.receive(again));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("重复"));
    }

    private Map<String, Object> receivePayload(String serialNo) {
        return Map.of(
                "supplierId", supplierId,
                "arrivalDate", LocalDate.now().toString(),
                "serialNo", serialNo,
                "lines", List.of(Map.of("itemCode", itemCode, "receivedQty", 10)));
    }

    // ---------- 13.2.2 上架扫码 ----------

    @Test
    void putawayStorageConditionHardBlocks() {
        // CHILLED 物料扫 AMBIENT 货位 → 服务层 422 → 移动端 L1 409
        String grNo = seedGrLine("50", "RELEASED");
        seedZoneBins();
        Map<String, Object> payload = Map.of("grNo", grNo, "lineNo", 1,
                "binCode", P + "BIN-AMBIENT");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.putaway(payload));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("L1"));
    }

    @Test
    void putawayMatchedBinSucceeds() {
        String grNo = seedGrLine("50", "RELEASED");
        seedZoneBins();
        Map<String, Object> r = service.putaway(Map.of("grNo", grNo, "lineNo", 1,
                "binCode", P + "BIN-CHILLED"));
        assertEquals("OK", r.get("level"));
        assertEquals(P + "BIN-CHILLED", r.get("binCode"));
    }

    // ---------- 13.2.3 拣货扫码 ----------

    @Test
    void pickBatchMismatchFreezesBinAndNotifies() {
        String taskId = seedPickTask();
        ServiceException ex = assertThrows(ServiceException.class, () -> service.pickScan(
                Map.of("taskId", taskId, "lineNo", 1, "binCode", P + "BIN-PK",
                        "itemCode", itemCode, "batchNo", "WRONG-BATCH-XYZ")));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("冻结"));
        // 冻结记录落库（ACTIVE）
        Integer fz = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_freeze WHERE ITEM_CODE=? AND STATUS='ACTIVE'",
                Integer.class, itemCode);
        assertEquals(1, fz, "批次不一致冻结货位落库");
        // 通知仓库主管待办
        Integer todo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_mobile_todo WHERE TITLE LIKE '%批次不一致%' AND STATUS='PENDING'",
                Integer.class);
        assertTrue(todo >= 1, "主管核查待办生成");
    }

    @Test
    void pickThreeCodesMatchPasses() {
        String taskId = seedPickTask();
        Map<String, Object> r = service.pickScan(Map.of("taskId", taskId, "lineNo", 1,
                "binCode", P + "BIN-PK", "itemCode", itemCode, "batchNo", P + "BATCH-1"));
        assertEquals("OK", r.get("level"));
    }

    // ---------- 13.2.4 发货扫码复核 ----------

    @Test
    void shipReviewQtyOverPlanHardBlocks() {
        String shipId = seedShipment("10");
        Map<String, Object> payload = Map.of("shipId", shipId, "itemCode", itemCode,
                "qty", new BigDecimal("15"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.shipReview(payload));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("L1"));
        assertTrue(ex.getMessage().contains("停止发货"));
    }

    @Test
    void shipReviewMatchPasses() {
        String shipId = seedShipment("10");
        Map<String, Object> r = service.shipReview(Map.of("shipId", shipId,
                "itemCode", itemCode, "qty", new BigDecimal("10")));
        assertEquals("OK", r.get("level"));
        assertEquals(0, new BigDecimal("10").compareTo(
                new BigDecimal(String.valueOf(r.get("planQty")))));
    }

    // ---------- 13.2.5 盘点扫码 ----------

    @Test
    void countWithinToleranceAutoAdjusts() {
        String lineId = seedCountLine("100");
        // 精确盘（差异 0）→ 容差内分支 → AUTO_ADJUSTED，无需库存行
        Map<String, Object> r = service.countScan(Map.of("lineId", lineId,
                "actualQty", new BigDecimal("100")));
        assertEquals("AUTO_ADJUSTED", r.get("outcome"));
        assertEquals("OK", r.get("level"));
    }

    @Test
    void countOverToleranceMapsToL2Approval() {
        String lineId = seedCountLine("100");
        Map<String, Object> r = service.countScan(Map.of("lineId", lineId,
                "actualQty", new BigDecimal("95")));
        assertEquals("PENDING_APPROVAL", r.get("outcome"));
        assertEquals("L2", r.get("level"));
        assertTrue(String.valueOf(r.get("levelHint")).contains("L2"));
    }

    @Test
    void countOverTenPercentBlockedForRecount() {
        String lineId = seedCountLine("100");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.countScan(Map.of("lineId", lineId,
                        "actualQty", new BigDecimal("80"))));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("复盘"));
    }

    // ---------- seeds ----------

    /** GR（CREATED）+ 行（PENDING / QC RELEASED / 核销量 50） */
    private String seedGrLine(String withinQty, String qc) {
        String grNo = P + "GR-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, SUPPLIER_ID, SUPPLIER_NAME, " +
                        "ARRIVAL_DATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'PO', ?, '扫码测试供应商', ?, 'CREATED', 'junit', '0', 0)",
                grNo + "-id", grNo, supplierId, LocalDate.now());
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, " +
                        "ORDERED_QTY, OPEN_QTY, RECEIVED_QTY, WITHIN_TOLERANCE_QTY, QC_STATUS, STATUS, " +
                        "CREATE_BY, VER_NO) VALUES (?,?,1,?,?,'PC',?,?,?,?,?, 'PENDING','junit',0)",
                grNo + "-l1", grNo + "-id", itemCode, "扫码测试冷藏物料",
                new BigDecimal("50"), new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal(withinQty), qc);
        return grNo;
    }

    private void seedZoneBins() {
        String z = P + "ZN";
        jdbc.update("INSERT IGNORE INTO erp_inv_zone (ID, WH_CODE, ZONE_CODE, ZONE_NAME, SORT_ORDER, " +
                        "STATUS, CREATE_BY) VALUES (?,?,?, '扫码存储区', 10, '1','junit')",
                P + "zone-id", "WH-MAIN", z);
        jdbc.update("INSERT IGNORE INTO erp_inv_bin (ID, WH_CODE, ZONE_CODE, BIN_CODE, BIN_SEQ, COL_NO, " +
                        "LAYER_NO, BIN_TYPE, TEMP_LEVEL, STATUS, CREATE_BY) VALUES " +
                        "(?,?,?,'" + P + "BIN-CHILLED',1,1,1,'STORE','CHILLED','1','junit'), " +
                        "(?,?,?,'" + P + "BIN-AMBIENT',1,1,2,'STORE','AMBIENT','1','junit')",
                P + "bin-chilled", "WH-MAIN", z, P + "bin-ambient", "WH-MAIN", z);
    }

    /** 领料单 + 行 + 拣货任务（PICKING） */
    private String seedPickTask() {
        String doc = P + "MI-" + UUID.randomUUID().toString().substring(0, 8);
        String bin = P + "BIN-PK";
        jdbc.update("INSERT INTO erp_inv_zone (ID, WH_CODE, ZONE_CODE, ZONE_NAME, SORT_ORDER, STATUS, CREATE_BY) " +
                "VALUES (?,?,?,'扫码拣货区',10,'1','junit')", P + "zone-pk", "WH-MAIN", P + "ZPK");
        jdbc.update("INSERT IGNORE INTO erp_inv_bin (ID, WH_CODE, ZONE_CODE, BIN_CODE, BIN_SEQ, COL_NO, " +
                        "LAYER_NO, BIN_TYPE, TEMP_LEVEL, STATUS, CREATE_BY) VALUES (?,?,?,'" + bin + "',1,1,1," +
                        "'STORE','CHILLED','1','junit')",
                P + "bin-pk", "WH-MAIN", P + "ZPK");
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, " +
                        "EXPIRY_DATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,'1','junit','0',0)",
                P + "batch-id", P + "BATCH-1", itemCode, "扫码测试冷藏物料",
                LocalDate.now().minusDays(10).toString(), LocalDate.now().plusDays(300).toString());
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, " +
                        "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) " +
                        "VALUES (?,?,?,?,'" + P + "BATCH-1','" + bin + "',30,0,0,30,?,'junit')",
                P + "stock-pk", "WH-MAIN", itemCode, "扫码测试冷藏物料", LocalDate.now().toString());
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, " +
                        "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?,?, 'OWN', ?, 'DRAFT','junit','0',0)",
                P + "mi-id", doc, P + "WO-1");
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, " +
                        "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,1,?,?,'" + P + "BATCH-1','" + bin + "',30,'OWN','junit','0',0)",
                P + "mi-l1", P + "mi-id", itemCode, "扫码测试冷藏物料");
        PickTask t = pickTaskService.createFromDoc("MATERIAL_OUT", doc);
        pickTaskService.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_PICKING);
        return t.getId();
    }

    /** 发货单 + 行（清单数量 10） */
    private String seedShipment(String planQty) {
        String shipNo = P + "SHIP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, CUSTOMER_CODE, " +
                        "CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'PARTIAL', 'CUST-IOT', 'CUST-IOT', '扫码测试客户', 'WH-MAIN', 'DRAFT', ?, 0, 'junit','0',0)",
                shipNo + "-id", shipNo, new BigDecimal(planQty));
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, SO_LINE_NO, " +
                        "ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, LINE_STATUS, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,1,'so-iot','so-iot-l1',1,?,? ,?,'WH-MAIN','PENDING','junit','0',0)",
                shipNo + "-l1", shipNo + "-id", itemCode, "扫码测试冷藏物料", new BigDecimal(planQty));
        return shipNo + "-id";
    }

    /** 盘点任务（COUNTING）+ 行（BOOK_QTY=100） */
    private String seedCountLine(String bookQty) {
        String taskNo = P + "CT-" + UUID.randomUUID().toString().substring(0, 8);
        String taskId = taskNo + "-id";
        String lineId = taskNo + "-l1";
        jdbc.update("INSERT INTO erp_inv_count_task (ID, TASK_NO, TASK_TYPE, WAREHOUSE_CODE, STATUS, " +
                        "TOTAL_LINES, COUNTED_LINES, ADJUSTED_LINES, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'CYCLE', 'WH-MAIN', 'COUNTING', 1, 0, 0, 'junit','0',0)",
                taskId, taskNo);
        jdbc.update("INSERT INTO erp_inv_count_line (ID, TASK_ID, BIN_CODE, ITEM_CODE, ITEM_NAME, " +
                        "BATCH_NO, BOOK_QTY, COUNT_STATUS, AUTO_ADJUST, CREATE_BY, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, '" + P + "BIN-CT', ?, '扫码测试冷藏物料', '" + P + "BATCH-1', ?, 'PENDING','0','junit','0',0)",
                lineId, taskId, itemCode, new BigDecimal(bookQty));
        return lineId;
    }
}

package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.inv.BinAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓位分配 DB 集成（真实 MySQL，spec bin-assignment，任务 4.1~4.6）：
 * 台账状态机与改派留痕、推荐过滤漏斗（类型/合规/容量）与三级排序、挂起+通知、
 * 队列聚合、上架合并/移位（物理删源行、数量守恒、不写流水）、权限（401/403）。
 */
@SpringBootTest
class BinAssignmentDbTest {

    // GR 无仓库维度，过账统一落 DEFAULT_WH（WH-MAIN）——仓位夹具须在同仓
    private static final String WH = "WH-MAIN";
    private static final String ITEM = "IT-BA-01";
    private static final String ITEM_FROZEN = "IT-BA-03";
    private static final String GR_NO = "BA-GR-1";
    private static final String GR_FROZEN = "BA-GR-3";
    private static final String BIN_OK = "BA-A-01-01-01";
    private static final String BIN_OK2 = "BA-A-01-01-03";
    private static final String BIN_AMBIENT = "BA-A-01-01-02";
    private static final String BIN_FULL = "BA-C-01-01-01";
    private static final String BIN_RET = "BA-R-01-01-01";

    @Autowired
    private BinAssignmentService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asWarehouse();

        // 区域：存储区（sort 10）+ 退货区（sort 20）
        jdbc.update("INSERT INTO erp_inv_zone (ID, WH_CODE, ZONE_CODE, ZONE_NAME, SORT_ORDER, "
                + "STATUS, CREATE_BY) VALUES ('ba-z-store', ?, 'BA-ZS', '存储区A', 10, '1', 'junit'), "
                + "('ba-z-ret', ?, 'BA-ZR', '退货区A', 20, '1', 'junit')", WH, WH);
        // 仓位：CHILLED 存储位 ×2、AMBIENT 存储位、满容量位（cap=1）、退货位
        jdbc.update("INSERT INTO erp_inv_bin (ID, WH_CODE, ZONE_CODE, BIN_CODE, BIN_SEQ, COL_NO, "
                + "LAYER_NO, BIN_TYPE, TEMP_LEVEL, CAPACITY_PALLET, STATUS, CREATE_BY) VALUES "
                + "('ba-b1', ?, 'BA-ZS', '" + BIN_OK + "', 1, 1, 1, 'STORE', 'CHILLED', NULL, '1', 'junit'), "
                + "('ba-b2', ?, 'BA-ZS', '" + BIN_OK2 + "', 1, 1, 3, 'STORE', 'CHILLED', NULL, '1', 'junit'), "
                + "('ba-b3', ?, 'BA-ZS', '" + BIN_AMBIENT + "', 1, 1, 2, 'STORE', 'AMBIENT', NULL, '1', 'junit'), "
                + "('ba-b4', ?, 'BA-ZS', '" + BIN_FULL + "', 1, 2, 1, 'STORE', 'CHILLED', 1, '1', 'junit'), "
                + "('ba-b5', ?, 'BA-ZR', '" + BIN_RET + "', 1, 1, 1, 'RETURN', 'CHILLED', NULL, '1', 'junit')",
                WH, WH, WH, WH, WH);
        // 物料：冷藏物料 + 冷冻物料（无匹配仓位 → 挂起）
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, TEMP_LEVEL, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('ba-it-1', ?, '分配测试冷藏物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', 'CHILLED', '0', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, TEMP_LEVEL, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('ba-it-3', ?, '分配测试冷冻物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', 'FROZEN', '0', '1', 'junit')", ITEM_FROZEN);
        // 收货单：放行行（核销 50）+ 让步行（CONCESSION）+ 冷冻物料行（无候选）
        insertGr(GR_NO);
        insertLine("ba-gl-1", GR_NO + "-id", 1, ITEM, "50", "RELEASED");
        insertLine("ba-gl-2", GR_NO + "-id", 2, ITEM, "30", "CONCESSION");
        insertGr(GR_FROZEN);
        insertLine("ba-gl-3", GR_FROZEN + "-id", 1, ITEM_FROZEN, "20", "RELEASED");
        // 满容量位已有 1 个有货批次（cap=1 → 新建行占位超限）
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('ba-st-full', ?, 'IT-OTHER', '他物料', 'B-FULL', '" + BIN_FULL + "', 5, 0, 0, 5, "
                + "CURDATE(), 'junit')", WH);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_putaway WHERE ITEM_CODE LIKE 'IT-BA%' "
                + "OR SOURCE_DOC_NO LIKE 'BA-%' OR ITEM_CODE = 'IT-OTHER'");
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE ID LIKE 'ba-gl-%'");
        jdbc.update("DELETE FROM erp_proc_gr WHERE GR_NO LIKE 'BA-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'ba-st-%'");
        jdbc.update("DELETE FROM erp_inv_bin WHERE WH_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_zone WHERE WH_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-BA%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE = 'BIN_ASSIGN'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE EVENT_TYPE = 'STOCK.MOVED' AND SOURCE LIKE 'BA-%'");
    }

    private void insertGr(String grNo) {
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, SUPPLIER_ID, SUPPLIER_NAME, "
                + "ARRIVAL_DATE, STATUS, BATCH_NO, CREATE_BY) VALUES (?, ?, 'PO', 'sup-ba', "
                + "'分配测试供应商', CURDATE(), 'CREATED', ?, 'junit')",
                grNo + "-id", grNo, grNo.equals(GR_NO) ? "BA-B1" : "BA-B3");
    }

    private void insertLine(String id, String grId, int lineNo, String item, String qty, String qc) {
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "ORDERED_QTY, OPEN_QTY, RECEIVED_QTY, WITHIN_TOLERANCE_QTY, QC_STATUS, STATUS, "
                + "CREATE_BY) VALUES (?, ?, ?, ?, '分配测试物料', 'PC', ?, ?, ?, ?, ?, 'PENDING', 'junit')",
                id, grId, lineNo, item, qty, qty, qty, qty, qc);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }

    // ================= 4.1/4.2 推荐器与台账 =================

    @Test
    void recommendConfirmFlowWithFunnelAndSorting() {
        // 队列：PENDING 起步
        Map<String, Object> q = service.pendingGrLines("BA-GR-1", 1, 20);
        assertEquals(2, rows(q).size(), "放行+让步两行入队");
        assertEquals("PENDING", rows(q).get(0).get("allocationStatus"));

        // 候选漏斗：AMBIENT 不合规剔除、退货位类型不符剔除、满容量剔除 → 仅 CHILLED 存储位 ×2，
        // 同区按仓位编号字典序（= 物理行走序）Top1 = BA-A-01-01-01
        List<Map<String, Object>> cands = service.candidates(GR_NO, 1);
        assertEquals(2, cands.size(), "候选=" + cands);
        assertEquals(BIN_OK, cands.get(0).get("binCode"));
        assertEquals(BIN_OK2, cands.get(1).get("binCode"));
        assertFalse(((List<?>) cands.get(0).get("reasons")).isEmpty(), "带入选理由");

        // 推荐 → RECOMMENDED → 确认 → CONFIRMED
        Map<String, Object> rec = service.recommend(GR_NO, 1);
        assertEquals("RECOMMENDED", ((Map<?, ?>) rec.get("record")).get("status"));
        String id = String.valueOf(((Map<?, ?>) rec.get("record")).get("id"));
        service.confirm(id);
        Map<String, Object> q2 = service.pendingGrLines("BA-GR-1", 1, 20);
        Map<String, Object> line1 = rows(q2).stream()
                .filter(r -> ((Number) r.get("LINE_NO")).intValue() == 1).findFirst().orElseThrow();
        assertEquals("CONFIRMED", line1.get("allocationStatus"));
        assertEquals(BIN_OK, line1.get("binCode"));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) line1.get("confirmedQty")),
                "已确认量=核销量（强前置口径）");
        assertEquals(1, service.history(GR_NO).size());
    }

    @Test
    void assignIllegalRejectedLegalSupersedesHistory() {
        // 违规指定：AMBIENT 不合规 → 422 提示合法清单
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.assign(GR_NO, 1, BIN_AMBIENT));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不满足合规"), ex.getMessage());
        // 违规指定：退货位对正常行（类型分流）→ 422
        assertThrows(ServiceException.class, () -> service.assign(GR_NO, 1, BIN_RET));

        // 合法指定 → CONFIRMED；改派 → 新记录替代、旧记录留痕
        service.assign(GR_NO, 1, BIN_OK);
        List<Map<String, Object>> h1 = service.history(GR_NO);
        assertEquals(1, h1.size());
        String firstId = String.valueOf(h1.get(0).get("id"));

        service.assign(GR_NO, 1, BIN_OK2);
        List<Map<String, Object>> h2 = service.history(GR_NO);
        assertEquals(2, h2.size(), "改派生成新记录");
        Map<String, Object> oldRec = h2.stream()
                .filter(r -> firstId.equals(String.valueOf(r.get("id")))).findFirst().orElseThrow();
        Map<String, Object> newRec = h2.stream()
                .filter(r -> !firstId.equals(String.valueOf(r.get("id")))).findFirst().orElseThrow();
        assertEquals(BIN_OK2, newRec.get("binCode"));
        assertEquals(newRec.get("id"), oldRec.get("supersededBy"), "旧记录被新记录替代（禁原地改）");
        assertEquals("CONFIRMED", newRec.get("status"));
    }

    @Test
    void noCandidatesSuspendsAndNotifies() {
        // 冷冻物料无匹配仓位 → SUSPENDED + 通知仓库主管（C-4.4-11）
        Map<String, Object> rec = service.recommend(GR_FROZEN, 1);
        assertEquals(Boolean.TRUE, rec.get("suspended"));
        assertEquals("SUSPENDED", ((Map<?, ?>) rec.get("record")).get("status"));
        assertEquals(0, ((List<?>) rec.get("candidates")).size());

        int notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE = 'BIN_ASSIGN'",
                Integer.class);
        assertTrue(notices >= 1, "挂起写通知");
        Map<String, Object> q = service.pendingGrLines("BA-GR-3", 1, 20);
        assertEquals(Boolean.TRUE, rows(q).get(0).get("suspended"));
        assertEquals("SUSPENDED", rows(q).get(0).get("allocationStatus"));

        // 挂起记录不可直接确认
        String susId = String.valueOf(((Map<?, ?>) rec.get("record")).get("id"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(susId));
        assertEquals(422, ex.getCode());
    }

    @Test
    void concessionLineOnlyRestrictedBins() {
        // 让步锁定行 → 仅退货/残次区（spec 类型分流）
        List<Map<String, Object>> cands = service.candidates(GR_NO, 2);
        assertEquals(1, cands.size());
        assertEquals(BIN_RET, cands.get(0).get("binCode"));
        assertEquals(Boolean.TRUE, cands.get(0).get("qcLocked"));
    }

    // ================= 4.4 上架（合并/移位） =================

    @Test
    void putawayMergeKeepsTotalAndDeletesSourcePhysically() {
        // 同批次：'' 位 40 + BA-A 位 60
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('ba-st-na', ?, ?, '分配测试冷藏物料', 'BA-BIN', '', 40, 0, 0, 40, CURDATE(), 'junit'), "
                + "('ba-st-tgt', ?, ?, '分配测试冷藏物料', 'BA-BIN', '" + BIN_OK + "', 60, 0, 0, 60, "
                + "CURDATE(), 'junit')", WH, ITEM, WH, ITEM);

        int txnBefore = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction",
                Integer.class);
        Map<String, Object> rec = service.recommendPutaway("ba-st-na");
        assertEquals(Boolean.FALSE, rec.get("suspended"));
        String id = String.valueOf(((Map<?, ?>) rec.get("record")).get("id"));
        assertEquals(BIN_OK, ((Map<?, ?>) rec.get("record")).get("binCode"), "同物料仓位优先");
        service.confirm(id);

        // 合并：目标 100、源行物理消失、批次跨位合计守恒、不写流水
        Map<String, Object> tgt = jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY FROM erp_inv_stock "
                + "WHERE ID = 'ba-st-tgt'");
        assertEquals(0, new BigDecimal("100").compareTo(new BigDecimal(String.valueOf(tgt.get("QTY")))));
        int srcLeft = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_stock WHERE ID = 'ba-st-na'", Integer.class);
        assertEquals(0, srcLeft, "源行物理删除（软删占键坑）");
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(QTY),0) FROM erp_inv_stock "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?",
                BigDecimal.class, WH, ITEM, "BA-BIN");
        assertEquals(0, new BigDecimal("100").compareTo(total), "数量守恒");
        int txnAfter = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction",
                Integer.class);
        assertEquals(txnBefore, txnAfter, "移位不写库存流水（偏差 D4）");
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_putaway WHERE ID = ?", String.class, id));
    }

    @Test
    void putawayMovesWhenTargetRowAbsent() {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('ba-st-move', ?, ?, '分配测试冷藏物料', 'BA-BIN2', '', 30, 0, 0, 30, CURDATE(), 'junit')",
                WH, ITEM);
        Map<String, Object> rec = service.recommendPutaway("ba-st-move");
        String id = String.valueOf(((Map<?, ?>) rec.get("record")).get("id"));
        service.confirm(id);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT BIN_CODE, QTY FROM erp_inv_stock WHERE ID = 'ba-st-move'");
        assertEquals(BIN_OK, row.get("BIN_CODE"), "目标无同行 → 仅移位");
        assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(String.valueOf(row.get("QTY")))));
        // 已分配行不可重复上架
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.recommendPutaway("ba-st-move"));
        assertEquals(422, ex.getCode());
    }

    // ================= 4.6 权限 =================

    @Test
    void writeRequiresWarehouseRole() {
        // 无认证 → 401（服务层）
        SecurityContextHolder.clearContext();
        ServiceException unauth = assertThrows(ServiceException.class,
                () -> service.recommend(GR_NO, 1));
        assertEquals(401, unauth.getCode());

        // 非授权角色 → 403
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_USER"));
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.assign(GR_NO, 1, BIN_OK));
        assertEquals(403, forbidden.getCode());
        int recs = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_putaway "
                + "WHERE SOURCE_DOC_NO = ?", Integer.class, GR_NO);
        assertEquals(0, recs, "403 未写入记录");

        // 读操作无需 WAREHOUSE 角色（仅认证）
        Map<String, Object> q = service.pendingGrLines("BA-GR-1", 1, 20);
        assertEquals(2, rows(q).size());
    }
}

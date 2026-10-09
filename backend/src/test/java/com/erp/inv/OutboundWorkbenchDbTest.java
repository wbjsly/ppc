package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.service.inv.OutboundWorkbenchService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.inv.TransferOrderService;
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
 * 出库作业台 DB 集成（真实 MySQL，spec outbound-workbench，任务 6.1/6.2/6.3 权限）：
 * 四类型队列口径与隔离、终态出队、物料摘要与关键词命中、挂起/会签标记、
 * 动作委托域服务（重复过账 422、未过账确认 422）、401/403。
 */
@SpringBootTest
class OutboundWorkbenchDbTest {

    private static final String ITEM = "IT-OB-01";

    @Autowired
    private OutboundWorkbenchService service;
    @Autowired
    private TransferOrderService transferService;
    @Autowired
    private ScrapOrderService scrapService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asWarehouse();

        // --- 销售：DRAFT / POSTED / SIGNED(终态不进队列) ---
        insertShipment("ob-sh-a", "OBSHIP-A", "DRAFT", "作业台客户A");
        insertShipment("ob-sh-b", "OBSHIP-B", "POSTED", "作业台客户B");
        insertShipment("ob-sh-c", "OBSHIP-C", "SIGNED", "作业台客户C");
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, "
                + "ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, LINE_STATUS, CREATE_BY) VALUES "
                + "('ob-spl-1', 'ob-sh-a', 1, 'ob-so', 'ob-sol', ?, '作业台测试物料', 5, 'WH-MAIN', "
                + "'PENDING', 'junit')", ITEM);

        // --- 领料：DRAFT(行 12) / POSTED ---
        insertIssue("ob-mi-a", "OBMI-A", "DRAFT");
        insertIssue("ob-mi-b", "OBMI-B", "POSTED");
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, QTY, STOCK_TYPE, CREATE_BY) VALUES "
                + "('ob-mil-1', 'ob-mi-a', 1, ?, '作业台测试物料', 'OB-B1', 12, 'OWN', 'junit')", ITEM);

        // --- 调拨：DRAFT / OUT_POSTED(挂起标记) ---
        insertTransfer("ob-tr-d", "OBTR-D", "DRAFT", "0");
        insertTransfer("ob-tr-o", "OBTR-O", "OUT_POSTED", "1");

        // --- 报废：DRAFT(不进队列A) / APPROVED(呆滞) / POSTED ---
        insertScrap("ob-sc-d", "OBSC-D", "DRAFT", "DAMAGE");
        insertScrap("ob-sc-a", "OBSC-A", "APPROVED", "STALE");
        insertScrap("ob-sc-p", "OBSC-P", "POSTED", "OTHER");

        // --- 报废过账夹具（库存 + 成本） ---
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, STATUS, CREATE_BY) "
                + "VALUES ('ob-it-1', ?, '作业台测试物料', '0001', 'PC', 'STRUCT', 'BUY', 'NORMAL', "
                + "'1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('ob-st-1', 'WH-MAIN', ?, '作业台测试物料', 'OB-B1', '', 100, 0, 0, 100, "
                + "'2026-09-20', 'junit')", ITEM);
    }

    /** 报废过账夹具（仅在过账用例内创建，避免污染队列A 的 APPROVED 断言） */
    private void seedScrapForPost() {
        jdbc.update("INSERT INTO erp_inv_scrap_order (ID, SCRAP_NO, REASON, WAREHOUSE_CODE, "
                + "STATUS, TOTAL_QTY, TOTAL_AMOUNT, CREATE_BY) VALUES "
                + "('ob-sc-act', 'OBSC-ACT', 'DAMAGE', 'WH-MAIN', 'APPROVED', 5, 10.00, 'tester')");
        jdbc.update("INSERT INTO erp_inv_scrap_order_line (ID, ORDER_ID, LINE_NO, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, QTY, STOCK_AGE_DAYS, UNIT_COST, LINE_AMOUNT, CREATE_BY) "
                + "VALUES ('ob-scl-1', 'ob-sc-act', 1, ?, '作业台测试物料', 'OB-B1', 5, 18, 2.00, "
                + "10.00, 'tester')", ITEM);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE ID LIKE 'ob-%' OR SHIP_ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ID LIKE 'ob-%' OR ISSUE_ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_inv_transfer_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM erp_inv_transfer_order WHERE ID LIKE 'ob-%')");
        jdbc.update("DELETE FROM erp_inv_transfer_order WHERE ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_inv_scrap_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM erp_inv_scrap_order WHERE ID LIKE 'ob-%' "
                + "OR SCRAP_NO LIKE 'OBSC%')");
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE ID LIKE 'ob-%' OR SCRAP_NO LIKE 'OBSC%'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_DOC_NO LIKE 'OBSC%')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_DOC_NO LIKE 'OBSC%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'ob-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-OB-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE LIKE 'IT-OB-%'");
        // outbox 幂等键含来源单号（如 OBSHIP-B:v1），跨轮次同号会撞 C-0-06
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'OBSHIP%' "
                + "OR IDEMPOTENCY_KEY LIKE 'OBTR%' OR IDEMPOTENCY_KEY LIKE 'OBMI%' "
                + "OR IDEMPOTENCY_KEY LIKE 'OBSC%' OR IDEMPOTENCY_KEY LIKE 'ob-tr%'");
    }

    private void insertShipment(String id, String no, String status, String customer) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, CREATE_BY) VALUES "
                + "(?, ?, 'PARTIAL', 'ob-cust', ?, 'WH-MAIN', ?, 5, 50.00, 'junit')",
                id, no, customer, status);
    }

    private void insertIssue(String id, String no, String status) {
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                + "STATUS, CREATE_BY) VALUES (?, ?, 'OWN', 'OB-WO-1', ?, 'junit')", id, no, status);
    }

    private void insertTransfer(String id, String no, String status, String suspended) {
        jdbc.update("INSERT INTO erp_inv_transfer_order (ID, TRANSFER_NO, OUT_WH_CODE, IN_WH_CODE, "
                + "OUT_LE_CODE, IN_LE_CODE, CROSS_LE, STATUS, SUSPENDED_FLAG, OUT_POST_AT, "
                + "TOTAL_QTY, TOTAL_AMOUNT, CREATE_BY) VALUES (?, ?, 'WH-MAIN', 'WH-02', "
                + "'LE-0001', 'LE-0001', '0', ?, ?, NOW(), 10, 100.00, 'junit')",
                id, no, status, suspended);
        // 行（过账引擎须有行；出库段扣 OB 库存）
        jdbc.update("INSERT INTO erp_inv_transfer_order_line (ID, ORDER_ID, LINE_NO, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, QTY, INTERNAL_PRICE, LINE_AMOUNT, CREATE_BY) VALUES "
                + "(?, ?, 1, ?, '作业台测试物料', 'OB-B1', 10, 10.00, 100.00, 'junit')",
                id + "-L1", id, ITEM);
    }

    private void insertScrap(String id, String no, String status, String reason) {
        jdbc.update("INSERT INTO erp_inv_scrap_order (ID, SCRAP_NO, REASON, WAREHOUSE_CODE, "
                + "STATUS, NCR_NO, TOTAL_QTY, TOTAL_AMOUNT, CREATE_BY) VALUES (?, ?, ?, 'WH-MAIN', "
                + "?, ?, 5, 10.00, 'junit')", id, no, reason, status,
                "QUALITY".equals(reason) ? "NCR-OB-1" : null);
    }

    // ================= 6.1 队列口径与隔离 =================

    @Test
    void salesQueueIsolationAndTerminalExcluded() {
        // 关键词圈定本测试夹具（防冒烟/E2E 残留污染）
        Map<String, Object> a = service.queue("SALES_OUT", "A", "OBSHIP", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsA = (List<Map<String, Object>>) a.get("rows");
        assertEquals(1, rowsA.size(), "队列A 仅 DRAFT：" + rowsA);
        assertEquals("OBSHIP-A", rowsA.get(0).get("docNo"));
        assertTrue(String.valueOf(rowsA.get(0).get("itemSummary")).contains("作业台测试物料"),
                "物料摘要：" + rowsA.get(0).get("itemSummary"));

        Map<String, Object> b = service.queue("SALES_OUT", "B", "OBSHIP", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsB = (List<Map<String, Object>>) b.get("rows");
        assertEquals(1, rowsB.size());
        assertEquals("OBSHIP-B", rowsB.get(0).get("docNo"));
        // 终态 SIGNED 不进任何队列
        assertFalse(rowsA.stream().anyMatch(r -> "OBSHIP-C".equals(r.get("docNo"))));
        assertFalse(rowsB.stream().anyMatch(r -> "OBSHIP-C".equals(r.get("docNo"))));

        // 关键词：来源单号 / 客户 / 物料命中
        assertEquals(1, ((Number) service.queue("SALES_OUT", "A", "OBSHIP-", 1, 20)
                .get("total")).intValue(), "单号关键词");
        assertEquals(1, ((Number) service.queue("SALES_OUT", "A", "作业台客户A", 1, 20)
                .get("total")).intValue(), "客户关键词");
        assertEquals(1, ((Number) service.queue("SALES_OUT", "A", ITEM, 1, 20)
                .get("total")).intValue(), "物料关键词命中行表");
        assertEquals(0, ((Number) service.queue("SALES_OUT", "A", "不存在的单ZZZ", 1, 20)
                .get("total")).intValue());
    }

    @Test
    void typeQueueStatusMapsAndMarkers() {
        // 领料：A=DRAFT（行合计 12）/ B=POSTED
        Map<String, Object> ma = service.queue("MATERIAL_OUT", "A", "OBMI", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mRows = (List<Map<String, Object>>) ma.get("rows");
        assertEquals(1, mRows.size());
        assertEquals("OBMI-A", mRows.get(0).get("docNo"));
        assertEquals(12L, ((Number) mRows.get(0).get("qty")).longValue(), "行合计");

        // 调拨：A=DRAFT / B=OUT_POSTED + 挂起标记（C-4.4-09）
        Map<String, Object> tb = service.queue("TRANSFER_OUT", "B", "OBTR", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tRows = (List<Map<String, Object>>) tb.get("rows");
        assertEquals(1, tRows.size());
        assertEquals("OBTR-O", tRows.get(0).get("docNo"));
        assertEquals("在途超期挂起", tRows.get(0).get("note"), "挂起标记");

        // 报废：A=APPROVED（DRAFT 不进）/ B=POSTED + 呆滞会签标记
        Map<String, Object> sa = service.queue("SCRAP_OUT", "A", "OBSC", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sRows = (List<Map<String, Object>>) sa.get("rows");
        assertEquals(1, sRows.size());
        assertEquals("OBSC-A", sRows.get(0).get("docNo"), "队列A 仅 APPROVED");
        assertTrue(String.valueOf(sRows.get(0).get("note")).contains("呆滞"), "会签来源标记");

        // 类型隔离：报废队列不见调拨单
        assertFalse(sRows.stream().anyMatch(r -> String.valueOf(r.get("docNo")).startsWith("OBTR")));

        // 不支持类型 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.queue("NOPE", "A", null, 1, 20));
        assertEquals(422, e.getCode());
    }

    // ================= 6.2 动作委托域服务 =================

    @Test
    void postActionsDelegateToDomainServices() {
        // 调拨出库经作业台过账 → 域服务同一动作；重复过账 422（双入口同结果）
        Map<String, Object> r1 = service.post("TRANSFER_OUT", "ob-tr-d");
        assertEquals("OUT_POSTED", r1.get("status"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.post("TRANSFER_OUT", "ob-tr-d"));
        assertEquals(422, e.getCode(), "重复过账 422（与 4.12.1 入口同结果）");

        // 报废经作业台过账 → POSTED（引擎+凭证一）
        seedScrapForPost();
        Map<String, Object> r2 = service.post("SCRAP_OUT", "ob-sc-act");
        assertEquals("POSTED", r2.get("status"));
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_DOC_NO = 'OBSC-ACT'",
                Integer.class);
        assertEquals(1, v, "委托过账产生凭证一");

        // 不支持类型 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.post("NOPE", "x"));
        assertEquals(422, e2.getCode());
    }

    @Test
    void confirmDelegatesAndRejectsDraft() {
        // 未过账确认 422（spec 场景：未过账确认被拒）
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.confirm("ob-sh-a", null, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("已过账"), e.getMessage());

        // POSTED → CONFIRMED（域服务同一动作；无行夹具不触发 SO 回写）
        Map<String, Object> r = service.confirm("ob-sh-b", "顺丰", "SF-OB-1");
        assertEquals("CONFIRMED", r.get("status"));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sd_shipment WHERE ID = 'ob-sh-b'", String.class));
    }

    // ================= 6.3 权限 =================

    @Test
    void permission401And403() {
        SecurityContextHolder.clearContext();
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.post("TRANSFER_OUT", "ob-tr-d"));
        assertEquals(401, e1.getCode());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", null, "ROLE_USER"));
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.post("TRANSFER_OUT", "ob-tr-d"));
        assertEquals(403, e2.getCode());
        // 查询仅需认证（有 token 即可——ROLE_USER 也放行查询）
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", null, "ROLE_USER"));
        Map<String, Object> q = service.queue("SALES_OUT", "A", "OBSHIP", 1, 5);
        assertEquals(1, ((Number) q.get("total")).intValue(), "查询不要求角色");
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }
}

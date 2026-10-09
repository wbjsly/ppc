package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调拨单 DB 集成（真实 MySQL，spec transfer-order，任务 2.2/2.3/2.4）：
 * 创建校验与 LE 解析、状态机双段过账、库存不足/重复/未出库先入库阻断、
 * 在途列表、权限 401/403。
 */
@SpringBootTest
class TransferOrderDbTest {

    // WH-MAIN(LE-0001) → WH-LE2(LE-0002) 跨法人；WH-MAIN → WH-02 同法人
    private static final String WH_OUT = "WH-MAIN";
    private static final String WH_IN_CROSS = "WH-LE2";
    private static final String WH_IN_SAME = "WH-02";
    private static final String ITEM = "IT-TO-01";

    @Autowired
    private TransferOrderService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asWarehouse();
        // 物料 + 调出仓库存 100（批次 T-B1，未分配位）
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('to-it-1', ?, '调拨测试物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('to-st-1', ?, ?, '调拨测试物料', 'T-B1', '', 100, 0, 0, 100, '2026-09-01', 'junit')",
                WH_OUT, ITEM);
        // 批次台账（真实流由收货过账建档；引擎 IN 对批次管理物料要求效期 BR-4.1-08）
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, EXPIRY_DATE, "
                + "STATUS, CREATE_BY) VALUES ('to-lg-1', 'T-B1', ?, '调拨测试物料', '2027-06-30', "
                + "'1', 'junit')", ITEM);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_transfer_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM erp_inv_transfer_order WHERE CREATE_BY IN ('tester', 'junit') "
                + "OR CREATE_BY IS NULL)");
        jdbc.update("DELETE FROM erp_inv_transfer_order WHERE CREATE_BY IN ('tester', 'junit') "
                + "OR CREATE_BY IS NULL");
        // 按物料全清（引擎自建行 ID 为 UUID，不能按 to-st-% 前缀清）
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE 'IT-TO-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-TO-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE LIKE 'IT-TO-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE LIKE 'IT-TO-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE = 'TRANSFER_TRANSIT'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE SOURCE LIKE 'TR%' AND EVENT_TYPE LIKE 'STOCK.%'");
    }

    // ================= 2.2 创建 / 编辑 / 作废 =================

    @Test
    void createValidationAndLeResolution() {
        // 同仓 422
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create(head(WH_OUT, WH_OUT, null), List.of(line("50", "10"))));
        assertEquals(422, e1.getCode());

        // 转移价必填 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(head(WH_OUT, WH_IN_CROSS, null), List.of(line("50", null))));
        assertEquals(422, e2.getCode());

        // 数量非正 422
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.create(head(WH_OUT, WH_IN_CROSS, null), List.of(line("0", "10"))));
        assertEquals(422, e3.getCode());

        // 正常创建：跨法人解析 + 合计
        Map<String, Object> out = service.create(head(WH_OUT, WH_IN_CROSS, "测试备注"),
                List.of(line("50", "10"), line("30", "12.5")));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        assertTrue(o.getTransferNo().startsWith("TR"), "单号 TR 前缀：" + o.getTransferNo());
        assertEquals("LE-0001", o.getOutLeCode());
        assertEquals("LE-0002", o.getInLeCode());
        assertEquals("1", o.getCrossLe(), "跨法人标记");
        assertEquals(InvTransferOrder.ST_DRAFT, o.getStatus());
        assertEquals(0, new BigDecimal("80").compareTo(o.getTotalQty()), "Σ行数量");
        assertEquals(0, new BigDecimal("875.00").compareTo(o.getTotalAmount()), "Σ行金额");
        @SuppressWarnings("unchecked")
        List<InvTransferOrderLine> lines = (List<InvTransferOrderLine>) out.get("lines");
        assertEquals(2, lines.size());
        assertEquals(1, lines.get(0).getLineNo());
    }

    @Test
    void sameLegalEntityNotCross() {
        Map<String, Object> out = service.create(head(WH_OUT, WH_IN_SAME, null),
                List.of(line("10", "5")));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        assertEquals("0", o.getCrossLe(), "同法人非跨法人（零凭证链）");
    }

    @Test
    void editAndCancelOnlyDraft() {
        Map<String, Object> out = service.create(head(WH_OUT, WH_IN_SAME, null),
                List.of(line("10", "5")));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        String id = o.getId();

        // 编辑（仅 DRAFT）
        Map<String, Object> upd = service.update(id, head(WH_OUT, WH_IN_SAME, "改备注"),
                List.of(line("20", "6")));
        @SuppressWarnings("unchecked")
        InvTransferOrder u = (InvTransferOrder) upd.get("order");
        assertEquals(0, new BigDecimal("20").compareTo(u.getTotalQty()));
        assertEquals("改备注", u.getRemark());

        // 作废原因必填
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.cancel(id, " "));
        assertEquals(422, e1.getCode());
        // 作废成功
        service.cancel(id, "计划取消");
        @SuppressWarnings("unchecked")
        InvTransferOrder c = (InvTransferOrder) service.detail(id).get("order");
        assertEquals(InvTransferOrder.ST_CANCELLED, c.getStatus());

        // 已作废不可再编辑
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.update(id, head(WH_OUT, WH_IN_SAME, null), List.of(line("1", "1"))));
        assertEquals(422, e2.getCode());
    }

    // ================= 2.3 双段过账 =================

    @Test
    void fullLifecycleSingleLeNoAccountingSideEffects() {
        String id = createOrder(WH_OUT, WH_IN_SAME, "10");

        // 出库过账：DRAFT → OUT_POSTED，扣减调出仓，分配明细回写
        Map<String, Object> afterOut = service.postOut(id);
        @SuppressWarnings("unchecked")
        InvTransferOrder o1 = (InvTransferOrder) afterOut.get("order");
        assertEquals(InvTransferOrder.ST_OUT_POSTED, o1.getStatus());
        assertNotNull(o1.getOutPostAt());
        assertNotNull(o1.getAllocJson(), "分配明细回写");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) afterOut.get("allocs");
        assertEquals(1, allocs.size());
        assertEquals("T-B1", allocs.get(0).get("batchNo"));
        BigDecimal outQty = jdbc.queryForObject(
                "SELECT QTY FROM erp_inv_stock WHERE ID = 'to-st-1'", BigDecimal.class);
        assertEquals(0, new BigDecimal("90").compareTo(outQty), "调出仓扣减 10");
        int outTxn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_NO = ? "
                + "AND TYPE_CODE = 'TRANSFER_OUT'", Integer.class, o1.getTransferNo());
        assertEquals(1, outTxn, "TRANSFER_OUT 流水 1 条");

        // 重复出库 422
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.postOut(id));
        assertEquals(422, e1.getCode());

        // 在途列表可见
        Map<String, Object> transit = service.intransitPage(1, 20, o1.getTransferNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tRows = (List<Map<String, Object>>) transit.get("rows");
        assertEquals(1, tRows.size());
        assertEquals("0", tRows.get(0).get("suspendedFlag"));

        // 关闭被拒（未入库）
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.close(id));
        assertEquals(422, e2.getCode());

        // 入库过账：OUT_POSTED → IN_POSTED，调入仓增加，退出在途
        Map<String, Object> afterIn = service.postIn(id);
        @SuppressWarnings("unchecked")
        InvTransferOrder o2 = (InvTransferOrder) afterIn.get("order");
        assertEquals(InvTransferOrder.ST_IN_POSTED, o2.getStatus());
        BigDecimal inQty = jdbc.queryForObject(
                "SELECT QTY FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? "
                + "AND BATCH_NO = 'T-B1'", BigDecimal.class, WH_IN_SAME, ITEM);
        assertEquals(0, new BigDecimal("10").compareTo(inQty), "调入仓按分配批次入账");
        assertEquals(0, ((Number) service.intransitPage(1, 20, o1.getTransferNo()).get("total")).intValue(),
                "退出在途列表");

        // 重复入库 422
        ServiceException e3 = assertThrows(ServiceException.class, () -> service.postIn(id));
        assertEquals(422, e3.getCode());

        // 关闭成功（同法人无核销项）
        Map<String, Object> closed = service.close(id);
        @SuppressWarnings("unchecked")
        InvTransferOrder o3 = (InvTransferOrder) closed.get("order");
        assertEquals(InvTransferOrder.ST_CLOSED, o3.getStatus());
        assertNotNull(o3.getCloseAt());
        // 关闭后只读
        ServiceException e4 = assertThrows(ServiceException.class, () -> service.postOut(id));
        assertEquals(422, e4.getCode());
    }

    @Test
    void insufficientStockBlocksOutPost() {
        String id = createOrder(WH_OUT, WH_IN_SAME, "150"); // 库存 100
        ServiceException e = assertThrows(ServiceException.class, () -> service.postOut(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("库存不足"), "负库存文案：" + e.getMessage());
        // 状态保持 DRAFT、无流水残留
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) service.detail(id).get("order");
        assertEquals(InvTransferOrder.ST_DRAFT, o.getStatus());
        Integer txn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE ITEM_CODE = ?", Integer.class, ITEM);
        assertEquals(0, txn, "无流水残留");
    }

    @Test
    void postInBeforePostOutRejected() {
        String id = createOrder(WH_OUT, WH_IN_SAME, "10");
        ServiceException e = assertThrows(ServiceException.class, () -> service.postIn(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("须先完成出库过账"), e.getMessage());
    }

    @Test
    void crossLeOrderMarkedForAccounting() {
        Map<String, Object> out = service.create(head(WH_OUT, WH_IN_CROSS, null),
                List.of(line("10", "5")));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        assertEquals("1", o.getCrossLe(), "跨法人标记（凭证/发票链触发条件）");
        // 出库过账成功（task 3.3 接入核算前，仅引擎链）
        Map<String, Object> afterOut = service.postOut(o.getId());
        @SuppressWarnings("unchecked")
        InvTransferOrder o2 = (InvTransferOrder) afterOut.get("order");
        assertEquals(InvTransferOrder.ST_OUT_POSTED, o2.getStatus());
    }

    // ================= 5.1 在途超期挂起（C-4.4-09） =================

    @Test
    void transitOverdueSuspendsIdempotently() {
        String id = createOrder(WH_OUT, WH_IN_SAME, "10");
        service.postOut(id);
        @SuppressWarnings("unchecked")
        InvTransferOrder o1 = (InvTransferOrder) service.detail(id).get("order");
        // 回拨出库时间 31 天（超 TRANSIT_ALERT_DAYS=30）
        jdbc.update("UPDATE erp_inv_transfer_order SET OUT_POST_AT = DATE_SUB(NOW(), INTERVAL 31 DAY) "
                + "WHERE ID = ?", id);

        int n1 = service.sweepTransitOverdue();
        assertEquals(1, n1, "首次扫描挂起 1 单");
        String flag = jdbc.queryForObject(
                "SELECT SUSPENDED_FLAG FROM erp_inv_transfer_order WHERE ID = ?", String.class, id);
        assertEquals("1", flag, "挂起标记置 1");
        Integer notice1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE = 'TRANSFER_TRANSIT' "
                        + "AND BIZ_ID = ?", Integer.class, id);
        assertEquals(1, notice1, "通知双方主管落表");

        // 二次扫描幂等：不重复挂起、不重复通知
        int n2 = service.sweepTransitOverdue();
        assertEquals(0, n2, "重复扫描不重复处理");
        Integer notice2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE = 'TRANSFER_TRANSIT' "
                        + "AND BIZ_ID = ?", Integer.class, id);
        assertEquals(1, notice2, "通知不重复");

        // 在途列表可见挂起标记与超期天数
        Map<String, Object> transit = service.intransitPage(1, 20, o1.getTransferNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) transit.get("rows");
        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).get("suspendedFlag"));
        assertTrue(((Number) rows.get(0).get("inTransitDays")).intValue() >= 31, "在途天数");

        // 入库过账后退出在途并清挂起
        service.postIn(id);
        assertEquals(0, ((Number) service.intransitPage(1, 20, o1.getTransferNo())
                .get("total")).intValue(), "入库后出列");
        assertEquals("0", jdbc.queryForObject(
                "SELECT SUSPENDED_FLAG FROM erp_inv_transfer_order WHERE ID = ?", String.class, id),
                "入库清挂起标记");
    }

    // ================= 2.4 权限 =================

    @Test
    void permission401And403() {
        SecurityContextHolder.clearContext();
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create(head(WH_OUT, WH_IN_SAME, null), List.of(line("1", "1"))));
        assertEquals(401, e1.getCode(), "未登录 401");

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", null, "ROLE_USER"));
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(head(WH_OUT, WH_IN_SAME, null), List.of(line("1", "1"))));
        assertEquals(403, e2.getCode(), "非授权 403");
    }

    // ---------- helpers ----------

    private String createOrder(String outWh, String inWh, String qty) {
        Map<String, Object> out = service.create(head(outWh, inWh, null), List.of(line(qty, "5")));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        return o.getId();
    }

    private InvTransferOrder head(String outWh, String inWh, String remark) {
        InvTransferOrder h = new InvTransferOrder();
        h.setOutWhCode(outWh);
        h.setInWhCode(inWh);
        h.setRemark(remark);
        return h;
    }

    private InvTransferOrderLine line(String qty, String price) {
        InvTransferOrderLine l = new InvTransferOrderLine();
        l.setItemCode(ITEM);
        l.setItemName("调拨测试物料");
        l.setQty(new BigDecimal(qty));
        if (price != null) {
            l.setInternalPrice(new BigDecimal(price));
        }
        return l;
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }
}

package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinInternalInvoiceDao;
import com.erp.entity.fin.FinInternalInvoice;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.InternalTransferAccountingService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨法人调拨内部核算 DB 集成（真实 MySQL，spec internal-transfer-accounting，任务 3.1~3.3）：
 * 税检 L1 前置阻断（无残留）、成对凭证与在途核销、内部发票配对核销、
 * 关闭核销校验、单法人零凭证、往来视图余额一致。
 */
@SpringBootTest
class InternalTransferAccountingDbTest {

    private static final String WH_OUT = "WH-MAIN";      // LE-0001
    private static final String WH_IN_CROSS = "WH-LE2";  // LE-0002
    private static final String WH_IN_SAME = "WH-02";    // LE-0001
    private static final String ITEM = "IT-IA-01";

    @Autowired
    private TransferOrderService service;
    @Autowired
    private InternalTransferAccountingService accounting;
    @Autowired
    private GlVoucherService voucherService;
    @Autowired
    private FinInternalInvoiceDao invoiceDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('ia-it-1', ?, '核算测试物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('ia-st-1', ?, ?, '核算测试物料', 'IA-B1', '', 500, 0, 0, 500, '2026-09-01', 'junit')",
                WH_OUT, ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, EXPIRY_DATE, "
                + "STATUS, CREATE_BY) VALUES ('ia-lg-1', 'IA-B1', ?, '核算测试物料', '2027-06-30', "
                + "'1', 'junit')", ITEM);
        // 保证 LE-0002 税号齐（防御上一用例残留）
        jdbc.update("UPDATE erp_mdm_legal_entity SET LOCAL_TAX_NO = '91320500MA1NX0000Y' "
                + "WHERE LE_CODE = 'LE-0002'");
    }

    @AfterEach
    void cleanup() {
        purge();
        // 恢复 LE-0002 税号（税检用例置空后须还原）
        jdbc.update("UPDATE erp_mdm_legal_entity SET LOCAL_TAX_NO = '91320500MA1NX0000Y' "
                + "WHERE LE_CODE = 'LE-0002' AND LOCAL_TAX_NO IS NULL");
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_internal_invoice WHERE CREATE_BY IN ('tester', 'junit') "
                + "OR CREATE_BY IS NULL");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TRANSFER' "
                + "AND CREATE_BY IN ('tester', 'junit'))");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TRANSFER' "
                + "AND CREATE_BY IN ('tester', 'junit')");
        jdbc.update("DELETE FROM erp_inv_transfer_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM erp_inv_transfer_order WHERE CREATE_BY IN ('tester', 'junit') "
                + "OR CREATE_BY IS NULL)");
        jdbc.update("DELETE FROM erp_inv_transfer_order WHERE CREATE_BY IN ('tester', 'junit') "
                + "OR CREATE_BY IS NULL");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE 'IT-IA-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-IA-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE LIKE 'IT-IA-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE LIKE 'IT-IA-%'");
    }

    // ================= 3.1 成对凭证 / 票配对 / 税检 =================

    @Test
    void crossLeFullChainVoucherAndInvoicePairing() {
        String id = createCrossOrder("100", "10");   // 金额 1000，含税 1130
        String no = (String) ((InvTransferOrder) service.detail(id).get("order")).getTransferNo();

        // 出库：凭证一 + OUT 票；关闭被核销校验阻断
        service.postOut(id);
        List<Map<String, Object>> v1 = voucherService.listBySource("TRANSFER", no);
        assertEquals(1, v1.size(), "出库段 1 张凭证");
        assertEquals(0, new BigDecimal("1000.00").compareTo(new BigDecimal(String.valueOf(v1.get(0).get("totalDr")))),
                "凭证金额 = Σ行金额");
        FinInternalInvoice outInv = findInvoice(no, FinInternalInvoice.DIR_OUT);
        assertNotNull(outInv, "内部销售票生成");
        assertEquals(FinInternalInvoice.ST_ISSUED, outInv.getStatus());
        assertEquals(0, new BigDecimal("1130.00").compareTo(outInv.getTotalAmount()), "含税 = 1000×1.13");

        Map<String, Object> check1 = accounting.settlementCheck(
                (InvTransferOrder) service.detail(id).get("order"));
        assertEquals(Boolean.FALSE, check1.get("settled"), "出库后未核销");
        @SuppressWarnings("unchecked")
        List<String> pending1 = (List<String>) check1.get("pending");
        assertTrue(pending1.stream().anyMatch(p -> p.contains("调拨在途凭证未核销")), pending1.toString());
        assertTrue(pending1.stream().anyMatch(p -> p.contains("内部发票未配对")), pending1.toString());
        // 出库段关闭被状态门阻断（仅 IN_POSTED 可关闭）
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.close(id));
        assertEquals(422, e1.getCode());

        // 入库：凭证二 + IN 票 + 自动配对
        service.postIn(id);
        List<Map<String, Object>> v2 = voucherService.listBySource("TRANSFER", no);
        assertEquals(2, v2.size(), "入库后成对凭证");
        FinInternalInvoice inInv = findInvoice(no, FinInternalInvoice.DIR_IN);
        assertNotNull(inInv, "内部采购票生成");
        assertEquals(FinInternalInvoice.ST_SETTLED, inInv.getStatus(), "IN 票已核销");
        FinInternalInvoice outAfter = findInvoice(no, FinInternalInvoice.DIR_OUT);
        assertEquals(FinInternalInvoice.ST_SETTLED, outAfter.getStatus(), "OUT 票已核销");
        assertEquals(inInv.getId(), outAfter.getPairId());
        assertEquals(outAfter.getId(), inInv.getPairId());

        // 核销校验通过 → 关闭成功
        Map<String, Object> check2 = accounting.settlementCheck(
                (InvTransferOrder) service.detail(id).get("order"));
        assertEquals(Boolean.TRUE, check2.get("settled"));

        // IN_POSTED 但票未核销 → 关闭被 422 未核销清单阻断（spec 场景：未核销清单返回）
        jdbc.update("UPDATE erp_fin_internal_invoice SET STATUS = 'ISSUED', PAIR_ID = NULL "
                + "WHERE TRANSFER_NO = ? AND DIRECTION = 'OUT'", no);
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.close(id));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("未核销"), e2.getMessage());
        assertTrue(e2.getMessage().contains("内部发票未配对"), e2.getMessage());
        // 恢复核销 → 关闭成功
        jdbc.update("UPDATE erp_fin_internal_invoice SET STATUS = 'SETTLED' "
                + "WHERE TRANSFER_NO = ? AND DIRECTION = 'OUT'", no);
        @SuppressWarnings("unchecked")
        InvTransferOrder closed = (InvTransferOrder) service.close(id).get("order");
        assertEquals(InvTransferOrder.ST_CLOSED, closed.getStatus());
    }

    @Test
    void taxMissingBlocksBeforeEngineNoResidue() {
        String id = createCrossOrder("10", "5");
        String no = (String) ((InvTransferOrder) service.detail(id).get("order")).getTransferNo();
        jdbc.update("UPDATE erp_mdm_legal_entity SET LOCAL_TAX_NO = NULL WHERE LE_CODE = 'LE-0002'");

        ServiceException e = assertThrows(ServiceException.class, () -> service.postOut(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("法人税务资质缺失"), e.getMessage());
        assertTrue(e.getMessage().contains("LE-0002.LOCAL_TAX_NO"), e.getMessage());
        // 无残留：状态 DRAFT、无凭证/票/流水
        assertEquals(InvTransferOrder.ST_DRAFT,
                ((InvTransferOrder) service.detail(id).get("order")).getStatus());
        assertEquals(0, voucherService.listBySource("TRANSFER", no).size(), "无凭证残留");
        assertEquals(0, countInvoice(no), "无票残留");
        Integer txn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE ITEM_CODE = ?", Integer.class, ITEM);
        assertEquals(0, txn, "无流水残留（税检在引擎前）");
    }

    @Test
    void singleLeZeroVouchersZeroInvoices() {
        String id = createOrder(WH_OUT, WH_IN_SAME, "10", "5");
        String no = (String) ((InvTransferOrder) service.detail(id).get("order")).getTransferNo();
        service.postOut(id);
        service.postIn(id);
        assertEquals(0, voucherService.listBySource("TRANSFER", no).size(), "单法人零凭证");
        assertEquals(0, countInvoice(no), "单法人零发票");
        // 关闭不受核销校验阻断
        InvTransferOrder closed = (InvTransferOrder) service.close(id).get("order");
        assertEquals(InvTransferOrder.ST_CLOSED, closed.getStatus());
    }

    // ================= 3.2 内部往来视图 =================

    @Test
    void receivableViewMatchesIssuedSum() {
        String id = createCrossOrder("100", "10");
        service.postOut(id);   // 出库后：OUT 票 ISSUED（未配对）

        Map<String, Object> view = accounting.receivableView();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) view.get("rows");
        Map<String, Object> row = rows.stream()
                .filter(r -> "LE-0001".equals(r.get("outLe")) && "LE-0002".equals(r.get("inLe"))
                        && "OUT".equals(r.get("direction")))
                .findFirst().orElse(null);
        assertNotNull(row, "视图含 LE-0001→LE-0002 OUT 行");
        // 余额 = ISSUED 未核销合计 = 1000×1.13 = 1130
        BigDecimal amount = new BigDecimal(String.valueOf(row.get("issuedAmount")));
        assertEquals(0, new BigDecimal("1130.00").compareTo(amount), "余额与台账一致");

        // 逐单下钻
        Map<String, Object> detail = accounting.receivableDetail("LE-0001", "LE-0002", "OUT", "ISSUED");
        @SuppressWarnings("unchecked")
        List<FinInternalInvoice> dRows = (List<FinInternalInvoice>) detail.get("rows");
        assertFalse(dRows.isEmpty(), "下钻明细非空");
        String no = dRows.get(0).getTransferNo();

        // 入库核销后余额归零
        service.postIn(id);
        Map<String, Object> view2 = accounting.receivableView();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows2 = (List<Map<String, Object>>) view2.get("rows");
        boolean stillIssued = rows2.stream()
                .anyMatch(r -> "LE-0001".equals(r.get("outLe")) && "LE-0002".equals(r.get("inLe"))
                        && "OUT".equals(r.get("direction")));
        assertFalse(stillIssued, "核销后 ISSUED 视图无该法人对");
        assertEquals(0, countStatus(no, "ISSUED"), "全部 SETTLED");
    }

    // ---------- helpers ----------

    private String createCrossOrder(String qty, String price) {
        return createOrder(WH_OUT, WH_IN_CROSS, qty, price);
    }

    private String createOrder(String outWh, String inWh, String qty, String price) {
        InvTransferOrder h = new InvTransferOrder();
        h.setOutWhCode(outWh);
        h.setInWhCode(inWh);
        InvTransferOrderLine l = new InvTransferOrderLine();
        l.setItemCode(ITEM);
        l.setItemName("核算测试物料");
        l.setQty(new BigDecimal(qty));
        l.setInternalPrice(new BigDecimal(price));
        Map<String, Object> out = service.create(h, List.of(l));
        @SuppressWarnings("unchecked")
        InvTransferOrder o = (InvTransferOrder) out.get("order");
        return o.getId();
    }

    private FinInternalInvoice findInvoice(String transferNo, String direction) {
        return invoiceDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<FinInternalInvoice>()
                .eq(FinInternalInvoice::getTransferNo, transferNo)
                .eq(FinInternalInvoice::getDirection, direction)
                .last("LIMIT 1"));
    }

    private int countInvoice(String transferNo) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_internal_invoice WHERE TRANSFER_NO = ?",
                Integer.class, transferNo);
        return n == null ? 0 : n;
    }

    private int countStatus(String transferNo, String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_internal_invoice WHERE TRANSFER_NO = ? AND STATUS = ?",
                Integer.class, transferNo, status);
        return n == null ? 0 : n;
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }
}

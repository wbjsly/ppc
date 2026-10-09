package com.erp.inv;

import com.erp.entity.inv.InvTransaction;
import com.erp.service.inv.InboundWorkbenchService;
import com.erp.service.inv.StockTransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 作业台与流水分页 DB 侧语义（真实 MySQL，spec inbound-workbench / stock-posting-engine，
 * 任务 5.2）：PURCHASE_IN 数据源（收货单+检验批状态）、RETURN_IN 镜像、未建域类型空态、
 * 类型不存在 422、流水分页与方向合计、双接口 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockWorkbenchDbTest {

    private static final String GR_NO = "WB-GR-001";
    private static final String RET_NO = "WB-RT-001";

    @Autowired
    private InboundWorkbenchService workbench;
    @Autowired
    private StockTransactionService transactionService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        purge();
        // 收货单 + 其检验批（POSTED，检验 SKIPPED 免检）
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, SUPPLIER_ID, SUPPLIER_NAME, "
                + "ARRIVAL_DATE, STATUS, CREATE_BY) VALUES "
                + "('wb-gr-1', ?, 'PO', 'sup-wb', '作业台供应商', CURDATE(), 'POSTED', 'junit')",
                GR_NO);
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, ITEM_CODE, GR_ID, STATUS, CREATE_BY) "
                + "VALUES ('wb-lot-1', 'LOT-WB-1', 'IT-WB-01', 'wb-gr-1', 'SKIPPED', 'junit')");
        // 销售退货单（镜像源）
        jdbc.update("INSERT INTO erp_sd_return (ID, RETURN_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "SO_NO, TOTAL_QTY, STATUS, CREATE_BY) VALUES "
                + "('wb-rt-1', ?, 'C-WB', '作业台客户', 'SO-WB-1', 5, 'STOCKED', 'junit')",
                RET_NO);
        // 流水两条（IN 100 / OUT 30，同单）
        jdbc.update("INSERT INTO erp_inv_transaction (ID, TXN_NO, DIRECTION, TYPE_CODE, "
                + "BIZ_DOC_TYPE, BIZ_DOC_NO, WAREHOUSE_CODE, ITEM_CODE, BATCH_NO, QTY, "
                + "BEFORE_QTY, AFTER_QTY, CREATE_BY) VALUES "
                + "('wb-tx-1', 'TXWB001', 'IN', 'PURCHASE_IN', 'GR', ?, 'WH-WB', 'IT-WB-01', "
                + "'B-WB', 100, 0, 100, 'junit'), "
                + "('wb-tx-2', 'TXWB002', 'OUT', 'SALES_OUT', 'SHIPMENT', ?, 'WH-WB', 'IT-WB-01', "
                + "'B-WB', 30, 100, 70, 'junit')", GR_NO, "SH-WB-001");
    }

    @AfterEach
    void cleanup() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_proc_gr WHERE GR_NO LIKE 'WB-GR-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE LOT_NO LIKE 'LOT-WB-%'");
        jdbc.update("DELETE FROM erp_sd_return WHERE RETURN_NO LIKE 'WB-RT-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = 'WH-WB'");
    }

    @Test
    void purchaseInSourceWithLotStatus() {
        Map<String, Object> out = workbench.tasks("PURCHASE_IN", GR_NO, null, 1, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) out.get("type");
        assertEquals("PURCHASE_IN", type.get("code"));
        assertEquals(1, ((Number) out.get("total")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        Map<String, Object> row = rows.get(0);
        assertEquals(GR_NO, row.get("docNo"));
        assertEquals("SKIPPED", row.get("lotStatus"), "检验批状态联动");
        assertEquals("POSTED", row.get("status"));
        // TINYINT 经 JDBC 读出为数值 1
        assertEquals(1, ((Number) type.get("enabled")).intValue());
    }

    @Test
    void returnInMirrorAndUnbuiltEmpty() {
        Map<String, Object> out = workbench.tasks("SALES_RETURN_IN", RET_NO, null, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(1, rows.size());
        assertEquals(RET_NO, rows.get(0).get("docNo"));
        assertEquals("SO-WB-1", rows.get(0).get("sourceDocNo"));

        // 未建域类型空态（骨架，偏差 D5）
        Map<String, Object> wip = workbench.tasks("WIP_IN", null, null, 1, 20);
        @SuppressWarnings("unchecked")
        List<?> empty = (List<?>) wip.get("rows");
        assertTrue(empty.isEmpty());
        assertEquals(0, ((Number) wip.get("total")).intValue());
    }

    @Test
    void unknownTypeRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(com.erp.common.ServiceException.class,
                () -> workbench.tasks("NOT_A_TYPE", null, null, 1, 20));
    }

    @Test
    void transactionPageAndDirectionSums() {
        Map<String, Object> out = transactionService.page(1, 20, null, null, null, null,
                "IT-WB-01", "WH-WB", null, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<InvTransaction> rows = (List<InvTransaction>) out.get("rows");
        assertEquals(2, rows.size());
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) out.get("sumIn")));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) out.get("sumOut")));

        // 方向过滤只过滤行、合计仍双向（对账口径）
        Map<String, Object> inOnly = transactionService.page(1, 20, null, null, null, "IN",
                "IT-WB-01", "WH-WB", null, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<InvTransaction> inRows = (List<InvTransaction>) inOnly.get("rows");
        assertEquals(1, inRows.size());
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) inOnly.get("sumIn")));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) inOnly.get("sumOut")));

        // 仓位筛选（change add-bin-assignment，任务 2.4）：种子流水落 '' 未分配位
        Map<String, Object> byBin = transactionService.page(1, 20, null, null, null, null,
                "IT-WB-01", "WH-WB", null, "", null, null, null);
        assertEquals(2, ((List<?>) byBin.get("rows")).size(), "按 '' 未分配位命中");
        Map<String, Object> noBin = transactionService.page(1, 20, null, null, null, null,
                "IT-WB-01", "WH-WB", null, "A-99-99-99", null, null, null);
        assertEquals(0, ((List<?>) noBin.get("rows")).size(), "不存在仓位过滤不命中");

        // 按来源单据下钻
        List<InvTransaction> byDoc = transactionService.byBizDoc("GR", GR_NO);
        assertEquals(1, byDoc.size());
        assertEquals("TXWB001", byDoc.get(0).getTxnNo());
    }

    @Test
    void unauthenticated401() throws Exception {
        mockMvc.perform(get("/api/inv/workbench/tasks").param("type", "PURCHASE_IN"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/inv/transactions"))
                .andExpect(status().isUnauthorized());
    }
}

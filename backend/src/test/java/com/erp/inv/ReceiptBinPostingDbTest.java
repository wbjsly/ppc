package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.proc.GoodsReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GR 过账仓位强前置（change add-bin-assignment，spec receipt-posting ①'，任务 5.1）：
 * 未分配 422 且 GR 保持 CREATED、部分分配 422、分配齐备过账成功且库存行/流水落在
 * CONFIRMED 分配的仓位（被 SUPERSEDED_BY 的旧记录不生效）。
 */
@SpringBootTest
class ReceiptBinPostingDbTest {

    private static final String GR_ID = "gp-gr-1";
    private static final String GR_NO = "GP-GR-1";
    private static final String ITEM = "IT-GP-01";
    private static final String BATCH = "GP-B1";
    private static final String BIN = "GP-BIN-1";

    @Autowired
    private GoodsReceiptService grService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, STATUS, "
                + "CREATE_BY) VALUES ('gp-sup', 'SUP-GP', '强前置测试供应商', 'QUALIFIED', 'junit')");
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, STATUS, CREATE_BY) "
                + "VALUES ('gp-it-1', ?, '强前置测试物料', '0001', 'PC', 'STRUCT', 'BUY', 'NORMAL', "
                + "'0', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, SOURCE_TYPE, SUPPLIER_ID, SUPPLIER_NAME, "
                + "ARRIVAL_DATE, STATUS, BATCH_NO, CREATE_BY) VALUES (?, ?, 'FREE', 'gp-sup', "
                + "'强前置测试供应商', CURDATE(), 'CREATED', ?, 'junit')", GR_ID, GR_NO, BATCH);
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "ORDERED_QTY, OPEN_QTY, RECEIVED_QTY, WITHIN_TOLERANCE_QTY, TOLERANCE_RESULT, "
                + "QC_STATUS, STATUS, CREATE_BY) VALUES ('gp-gl-1', ?, 1, ?, '强前置测试物料', 'PC', "
                + "40, 40, 40, 40, 'FREE', 'RELEASED', 'PENDING', 'junit')", GR_ID, ITEM);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE ID = 'gp-gl-1'");
        jdbc.update("DELETE FROM erp_proc_gr WHERE ID = ?", GR_ID);
        jdbc.update("DELETE FROM erp_inv_putaway WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE ID = 'gp-sup'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE EVENT_TYPE = 'STOCK.MOVED' "
                + "AND SOURCE LIKE '" + GR_NO + "%'");
    }

    private void insertConfirmed(String id, String bin, String qty, String supersededBy) {
        jdbc.update("INSERT INTO erp_inv_putaway (ID, SOURCE_TYPE, SOURCE_DOC_NO, SOURCE_LINE_NO, "
                + "WAREHOUSE_CODE, ITEM_CODE, BATCH_NO, QTY, BIN_CODE, STATUS, SUPERSEDED_BY, "
                + "CREATE_BY) VALUES (?, 'GR', ?, 1, 'WH-MAIN', ?, ?, ?, ?, 'CONFIRMED', ?, 'junit')",
                id, GR_NO, ITEM, BATCH, qty, bin, supersededBy);
    }

    @Test
    void unassignedBlockedAndGrStaysCreated() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> grService.posting(GR_ID));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("请先完成仓位分配"), ex.getMessage());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_proc_gr WHERE ID = ?", String.class, GR_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_stock "
                + "WHERE ITEM_CODE = ?", Integer.class, ITEM), "无库存残留");
    }

    @Test
    void partialAllocationBlocked() {
        insertConfirmed("gp-pw-30", BIN, "30", null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> grService.posting(GR_ID));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓位分配"), ex.getMessage());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_proc_gr WHERE ID = ?", String.class, GR_ID));
    }

    @Test
    void assignedPostsIntoConfirmedBin() {
        // 旧记录已被改派替代（错误仓位）——仅生效记录取位
        insertConfirmed("gp-pw-old", "OLD-BIN", "40", "gp-pw-new");
        insertConfirmed("gp-pw-new", BIN, "40", null);

        grService.posting(GR_ID);
        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_proc_gr WHERE ID = ?", String.class, GR_ID));

        Map<String, Object> stock = jdbc.queryForMap(
                "SELECT BIN_CODE, QTY, AVAILABLE_QTY FROM erp_inv_stock "
                        + "WHERE ITEM_CODE = ? AND BATCH_NO = ?", ITEM, BATCH);
        assertEquals(BIN, stock.get("BIN_CODE"), "库存行落在 CONFIRMED 分配仓位");
        assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(String.valueOf(stock.get("QTY")))));

        Map<String, Object> txn = jdbc.queryForMap(
                "SELECT BIN_CODE, TYPE_CODE, DIRECTION FROM erp_inv_transaction "
                        + "WHERE BIZ_DOC_NO = ? AND ITEM_CODE = ?", GR_NO, ITEM);
        assertEquals(BIN, txn.get("BIN_CODE"), "流水带仓位");
        assertEquals("PURCHASE_IN", txn.get("TYPE_CODE"));

        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_proc_gr_line WHERE ID = 'gp-gl-1'", String.class));
    }
}

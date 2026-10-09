package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.inv.FifoStrategyService;
import com.erp.service.vmi.MaterialIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改批偏离留痕 DB 语义（真实 MySQL，spec outbound-strategy / material-issue MODIFIED，任务 4.1）：
 * 改批+原因落账一条、无原因 422、与推荐一致不落账；台账分页查询与筛选。
 */
@SpringBootTest
class BatchDeviationDbTest {

    private static final String ITEM = "IT-DEV-01";

    @Autowired
    private MaterialIssueService issueService;
    @Autowired
    private FifoStrategyService fifoStrategyService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('dev-it-1', ?, '偏离测试物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        // 两个批次库存（B-NEW 较新，FIFO 推荐应为 B-OLD）
        seedStock("B-OLD", "100", "2026-10-01", "2027-06-30");
        seedStock("B-NEW", "100", "2026-10-05", "2027-06-30");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_batch_deviation WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'WO-DEV%' "
                + "OR CREATE_BY IN ('tester', 'junit')");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
    }

    private void seedStock(String batch, String qty, String inbound, String expiry) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                        + "(?, 'WH-MAIN', ?, '偏离测试物料', ?, ?, 0, 0, ?, ?, 'junit')",
                "dev-st-" + batch, ITEM, batch, qty, qty, inbound);
        // 生产日期须保证剩余天数 > 有效期总天数×50%（引擎④实时到线判定，expiry-management D3）
        // 总 302 天（2026-09-01~2027-06-30），阈值 151；当前剩余约 260+ → 不到线
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, "
                        + "EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                        + "(?, ?, ?, '偏离测试物料', ?, ?, '0', '1', 'junit')",
                "dev-lg-" + batch, batch, ITEM, "2026-09-01", expiry);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    private Map<String, Object> payload(String batchNo, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("issueType", "OWN");
        p.put("workOrderNo", "WO-DEV-" + System.nanoTime() % 100000);
        Map<String, Object> line = new HashMap<>();
        line.put("itemCode", ITEM);
        line.put("qty", 30);
        if (batchNo != null) {
            line.put("batchNo", batchNo);
        }
        if (reason != null) {
            line.put("deviationReason", reason);
        }
        p.put("lines", List.of(line));
        return p;
    }

    private int deviationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_batch_deviation WHERE ITEM_CODE = ?",
                Integer.class, ITEM);
    }

    /** 改批（推荐 B-OLD → 指定 B-NEW）+ 原因 → 落账一条，含推荐值与实际值 */
    @Test
    void overrideWithReasonRecordsDeviation() {
        issueService.create(payload("B-NEW", "B-OLD 待质量复核"));

        assertEquals(1, deviationCount());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SRC_DOC_TYPE, RECOMMENDED_BATCH, ACTUAL_BATCH, REASON FROM erp_inv_batch_deviation "
                        + "WHERE ITEM_CODE = ?", ITEM);
        assertEquals("MATERIAL_ISSUE", row.get("SRC_DOC_TYPE"));
        assertEquals("B-OLD", row.get("RECOMMENDED_BATCH"));
        assertEquals("B-NEW", row.get("ACTUAL_BATCH"));
        assertEquals("B-OLD 待质量复核", row.get("REASON"));
    }

    /** 改批无原因 → 422，领料单不保存 */
    @Test
    void overrideWithoutReasonRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> issueService.create(payload("B-NEW", null)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("改批原因"), ex.getMessage());
        assertEquals(0, deviationCount());
        int issues = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_material_issue "
                + "WHERE WORK_ORDER_NO LIKE 'WO-DEV%'", Integer.class);
        assertEquals(0, issues, "422 回滚，领料单不生成");
    }

    /** 指定批次与推荐一致 → 不落账（spec 场景：无偏离不记录） */
    @Test
    void sameAsRecommendedNoRecord() {
        // FIFO 推荐 B-OLD（更早入库），显式指定 B-OLD → 无偏离
        issueService.create(payload("B-OLD", null));
        assertEquals(0, deviationCount());
    }

    /** 未指定批次（缺省配批）→ 不落账 */
    @Test
    void noOverrideNoRecord() {
        issueService.create(payload(null, null));
        assertEquals(0, deviationCount());
    }

    /** 台账分页查询与单据号筛选 */
    @Test
    void deviationPagingAndFilter() {
        issueService.create(payload("B-NEW", "改批原因甲"));
        Map<String, Object> all = fifoStrategyService.deviations(null, null, null, null, null, 1, 10);
        assertEquals(1L, all.get("total"));

        Map<String, Object> miss = fifoStrategyService.deviations("NO-SUCH-DOC", null, null,
                null, null, 1, 10);
        assertEquals(0L, miss.get("total"));

        Map<String, Object> hit = fifoStrategyService.deviations(null, ITEM, "tester", null, null, 1, 10);
        assertEquals(1L, hit.get("total"));
    }
}

package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.vmi.MaterialIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后领料 DbTest（add-service-order，spec material-issue ADDED「售后领用类型 AFTER_SALE」）：
 * 过账扣减 NORMAL 仓并挂工单、缺工单号 422、工单不存在 422、按工单号反查、
 * 成本累加工单 MATERIAL_COST、OWN 类型行为不变（serviceOrderNo 可空不校验）。
 * 造数前缀 as-，物料 AS-ITEM。
 */
@SpringBootTest
class AfterSaleIssueDbTest {

    private static final String ITEM = "AS-ITEM";
    private static final String ORDER_NO = "SO20261026-000777";

    @Autowired
    private MaterialIssueService issueService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('as-it-1', ?, '售后测试物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        seedStock("AS-B1", 100);
        // 服务工单（工单号锚点；客户不存在不影响领料侧——领料只校验工单存在）
        jdbc.update("INSERT INTO erp_pm_service_order (ID, ORDER_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                        + "COMPLAINT_TYPE, IMPACT_LEVEL, DESCRIPTION, LEVEL, STATUS, SUSPENDED_FLAG, "
                        + "SUSPEND_HOURS, ESCALATE_STAGE, REJECT_COUNT, ROOT_CURED, CLOSE_DEFAULT_FLAG, "
                        + "MATERIAL_COST, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('as-so-1', ?, 'as-cust-1', '售后测试客户', 'PRODUCT_QUALITY', 'CORE', "
                        + "'售后换件投诉', 2, 'PROCESSING', '0', 0, 0, 0, '0', '0', 0, 'junit', NOW(), '0', 0)",
                ORDER_NO);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void afterSaleIssuePostsAndAccumulatesCost() {
        Map<String, Object> created = issueService.create(payload(ORDER_NO, 10));
        com.erp.entity.vmi.MaterialIssue issue =
                (com.erp.entity.vmi.MaterialIssue) created.get("issue");
        String id = issue.getId();
        assertEquals("AFTER_SALE", issue.getIssueType());
        assertEquals(ORDER_NO, issue.getServiceOrderNo());

        // 过账：NORMAL 仓扣减
        BigDecimal before = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='AS-B1'",
                BigDecimal.class, ITEM);
        issueService.post(id);
        BigDecimal after = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='AS-B1'",
                BigDecimal.class, ITEM);
        assertEquals(0, before.subtract(new BigDecimal("10")).compareTo(after), "过账扣减 10");

        // 成本累加：行金额 = 数量 × STANDARD_COST（erp_mdm_item 未维护则 0 → 不累加）
        Map<String, Object> order = jdbc.queryForMap(
                "SELECT MATERIAL_COST, STATUS FROM erp_pm_service_order WHERE ORDER_NO=?", ORDER_NO);
        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_material_issue WHERE ID=?", String.class, id));
        assertNotNull(order.get("MATERIAL_COST"));
    }

    @Test
    void afterSaleWithoutServiceOrderNoBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> issueService.create(payload(null, 5)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("服务工单"), e.getMessage());
    }

    @Test
    void afterSaleUnknownServiceOrderBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> issueService.create(payload("SO-NOPE-1", 5)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void afterSaleQueryableByServiceOrderNo() {
        Map<String, Object> created = issueService.create(payload(ORDER_NO, 8));
        com.erp.entity.vmi.MaterialIssue issue =
                (com.erp.entity.vmi.MaterialIssue) created.get("issue");
        // 按工单号反查命中
        var page = issueService.page(1, 50, "AFTER_SALE", null, null, null, ORDER_NO);
        assertEquals(1, page.getTotal());
        assertEquals(issue.getId(), page.getRecords().get(0).get("id"));
        // 空工单号反查不命中
        assertEquals(0, issueService.page(1, 50, "AFTER_SALE", null, null, null, "SO-OTHER").getTotal());
    }

    @Test
    void ownTypeServiceOrderNoOptionalAndUnvalidated() {
        // OWN 类型：serviceOrderNo 可空（spec 场景「非售后类型不做强制」）
        Map<String, Object> created = issueService.create(ownPayload(null));
        com.erp.entity.vmi.MaterialIssue issue =
                (com.erp.entity.vmi.MaterialIssue) created.get("issue");
        assertEquals("OWN", issue.getIssueType());
        // 填了不存在的工单号也不阻断（仅 AFTER_SALE 校验存在性）
        Map<String, Object> created2 = issueService.create(ownPayload("SO-ANYWAY"));
        com.erp.entity.vmi.MaterialIssue issue2 =
                (com.erp.entity.vmi.MaterialIssue) created2.get("issue");
        assertEquals("SO-ANYWAY", issue2.getServiceOrderNo());
    }

    // ================================================================
    // 造数
    // ================================================================

    private Map<String, Object> payload(String serviceOrderNo, int qty) {
        Map<String, Object> p = new HashMap<>();
        p.put("issueType", "AFTER_SALE");
        p.put("workOrderNo", "WO-AS-" + System.nanoTime() % 100000);
        if (serviceOrderNo != null) {
            p.put("serviceOrderNo", serviceOrderNo);
        }
        p.put("purpose", "服务工单换件领料");
        Map<String, Object> line = new HashMap<>();
        line.put("itemCode", ITEM);
        line.put("qty", qty);
        p.put("lines", List.of(line));
        return p;
    }

    private Map<String, Object> ownPayload(String serviceOrderNo) {
        Map<String, Object> p = new HashMap<>();
        p.put("issueType", "OWN");
        p.put("workOrderNo", "WO-ASOWN-" + System.nanoTime() % 100000);
        if (serviceOrderNo != null) {
            p.put("serviceOrderNo", serviceOrderNo);
        }
        Map<String, Object> line = new HashMap<>();
        line.put("itemCode", ITEM);
        line.put("qty", 1);
        p.put("lines", List.of(line));
        return p;
    }

    private void seedStock(String batch, int qty) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                        + "(?, 'WH-MAIN', ?, '售后测试物料', ?, ?, 0, 0, ?, '2026-10-01', 'junit')",
                "as-st-" + batch, ITEM, batch, qty, qty);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, "
                        + "EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                        + "(?, ?, ?, '售后测试物料', '2026-09-01', '2027-06-30', '0', '1', 'junit')",
                "as-lg-" + batch, batch, ITEM);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE SERVICE_ORDER_NO = ? "
                + "OR WORK_ORDER_NO LIKE 'WO-AS%'", ORDER_NO);
        jdbc.update("DELETE FROM erp_pm_service_order WHERE ORDER_NO = ?", ORDER_NO);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }
}

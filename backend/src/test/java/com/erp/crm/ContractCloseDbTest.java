package com.erp.crm;

import com.erp.common.ServiceException;
import com.erp.service.crm.ContractService;
import com.erp.service.sd.SoService;
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
 * 合同收口 DbTest（add-contract-close-delivery，spec sales-contract ADDED）：
 * 软终止（状态机/原因必填/限权 MGR/未关闭 SO 警告不阻断/force 落库）、
 * 终止后阻断（新增 SO 422/新增期次 422）、存量放行。
 * 造数前缀 ctd-。
 */
@SpringBootTest
class ContractCloseDbTest {

    @Autowired
    private ContractService service;
    @Autowired
    private SoService soService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String CUST = "ctd-cust-1";

    @BeforeEach
    void setUp() {
        purge();
        jdbc.update("INSERT INTO erp_mdm_customer_group (ID, CUSTOMER_CODE, CUSTOMER_NAME, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, 'CTD-CUST', 'CTD 收口客户', "
                + "'1', 'junit', '0', 0)", CUST);
        asMgr();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ==================== 终止状态机 ====================

    @Test
    void terminateSignedContract() {
        String id = seedContract("ctd-1", "SIGNED");
        Map<String, Object> out = service.terminate(id, "客户战略调整", false);
        // 落库验证
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, TERMINATE_REASON, CLOSED_BY, CLOSED_AT FROM erp_crm_contract WHERE ID=?", id);
        assertEquals("TERMINATED", row.get("STATUS"));
        assertEquals("客户战略调整", row.get("TERMINATE_REASON"));
        assertNotNull(row.get("CLOSED_BY"));
        assertNotNull(row.get("CLOSED_AT"));
        assertNotNull(out.get("contract"), "返回应含合同");
    }

    @Test
    void terminateReasonRequired() {
        String id = seedContract("ctd-2", "SIGNED");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.terminate(id, "  ", false));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("原因"), e.getMessage());
    }

    @Test
    void terminateForbiddenForSales() {
        String id = seedContract("ctd-3", "SIGNED");
        asRole("ROLE_SALES");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.terminate(id, "测试", false));
        assertEquals(403, e.getCode());
    }

    @Test
    void terminateDraftBlocked() {
        String id = seedContract("ctd-4", "DRAFT");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.terminate(id, "测试", false));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("不可终止"), e.getMessage());
    }

    @Test
    void terminateChangedContractAllowed() {
        String id = seedContract("ctd-5", "CHANGED");
        service.terminate(id, "变更后协商终止", false);
        assertEquals("TERMINATED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_crm_contract WHERE ID=?", String.class, id));
    }

    // ==================== 未关闭 SO 警告（L4 不阻断） ====================

    @Test
    void openSoWarnsWithoutBlocking() {
        String id = seedContract("ctd-6", "SIGNED");
        seedSo("ctd-so-1", id, "CONFIRMED");
        Map<String, Object> out = service.terminate(id, "客户中止", false);
        assertNotNull(out.get("l4Warning"), "有未关闭 SO 应返回 L4 警告");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> openSos = (List<Map<String, Object>>) out.get("openSos");
        assertEquals(1, openSos.size());
        // force=false 不落库
        assertEquals("SIGNED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_crm_contract WHERE ID=?", String.class, id));
    }

    @Test
    void forceTerminateWithOpenSo() {
        String id = seedContract("ctd-7", "SIGNED");
        seedSo("ctd-so-2", id, "CONFIRMED");
        Map<String, Object> out = service.terminate(id, "客户中止（确认存量走完）", true);
        assertNotNull(out.get("contract"));
        assertEquals("TERMINATED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_crm_contract WHERE ID=?", String.class, id));
        // 存量 SO 状态不变（照常走完）
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sd_so WHERE ID='ctd-so-2'", String.class));
    }

    // ==================== 终止后阻断 ====================

    @Test
    void newSoBlockedAfterTerminate() {
        String id = seedContract("ctd-8", "SIGNED");
        service.terminate(id, "测试阻断", true);
        ServiceException e = assertThrows(ServiceException.class, () -> soService.create(
                Map.of("customerId", CUST, "contractId", id, "orderType", "STANDARD"),
                List.of()));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("已终止"), e.getMessage());
    }

    @Test
    void newPlanBlockedAfterTerminate() {
        String id = seedContract("ctd-9", "SIGNED");
        service.terminate(id, "测试阻断", true);
        ServiceException e = assertThrows(ServiceException.class, () -> service.savePlans(id,
                List.of(Map.of("periodNo", 1, "planAmount", "1000", "dueDate", "2026-12-31"))));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("已终止"), e.getMessage());
    }

    // ==================== 交付执行聚合 + 期次软映射 ====================

    @Test
    void deliveryAggregatesThreeSos() {
        String id = seedContract("ctd-10", "SIGNED");
        // 3 张 SO 三态：已发货已开票 / 已发货未开票 / 未发货
        seedSo("ctd-so-a", id, "SHIPPED");
        seedSo("ctd-so-b", id, "PARTIAL_SHIPPED");
        seedSo("ctd-so-c", id, "CONFIRMED");
        seedShipmentLine("ctd-so-a", "ctd-ship-1", new BigDecimal("30000"));
        seedShipmentLine("ctd-so-b", "ctd-ship-2", new BigDecimal("10000"));
        seedAr("ctd-ar-1", "ctd-so-a", new BigDecimal("30000"), new BigDecimal("30000"));

        Map<String, Object> out = service.deliveryProgress(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("soRows");
        assertEquals(3, rows.size());
        Map<String, Object> rowA = rows.stream()
                .filter(r -> "SO-CTD-SO-A".equals(r.get("soNo"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("30000")
                .compareTo((BigDecimal) rowA.get("shippedAmount")), "A 行已发货 30000");
        assertEquals(0, new BigDecimal("30000")
                .compareTo((BigDecimal) rowA.get("invoicedAmount")), "A 行已开票 30000");
        assertEquals(0, new BigDecimal("30000")
                .compareTo((BigDecimal) rowA.get("writtenOffAmount")), "A 行已核销 30000");
        Map<String, Object> rowC = rows.stream()
                .filter(r -> "SO-CTD-SO-C".equals(r.get("soNo"))).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) rowC.get("shippedAmount")),
                "C 行未发货");
        // 汇总：合同额 100000，累计发货 40000 → 完成率 40%
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(0, new BigDecimal("40000")
                .compareTo((BigDecimal) summary.get("totalShipped")));
        assertEquals("40", summary.get("deliveryRatePct"));
    }

    @Test
    void periodWindowMatchesArLeftOpenRightClosed() {
        String id = seedContract("ctd-11", "SIGNED");
        // 合同 START_DATE=2026-01-01（seedContract 默认无 → 补 UPDATE）
        jdbc.update("UPDATE erp_crm_contract SET START_DATE='2026-01-01' WHERE ID=?", id);
        seedPlan("ctd-p1", id, 1, new BigDecimal("50000"), "2026-03-31");
        seedPlan("ctd-p2", id, 2, new BigDecimal("50000"), "2026-06-30");
        seedSo("ctd-so-d", id, "SHIPPED");
        // 3 张应收：3/31（落第 1 期右边界，右闭）、4/1（落第 2 期左开右）、7/1（未落窗）
        seedAr("ctd-ar-w1", "ctd-so-d", new BigDecimal("10000"), BigDecimal.ZERO, "2026-03-31");
        seedAr("ctd-ar-w2", "ctd-so-d", new BigDecimal("20000"), BigDecimal.ZERO, "2026-04-01");
        seedAr("ctd-ar-w3", "ctd-so-d", new BigDecimal("5000"), BigDecimal.ZERO, "2026-07-01");

        Map<String, Object> out = service.deliveryProgress(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) out.get("plans");
        assertEquals(2, plans.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> w1 = (List<Map<String, Object>>) plans.get(0).get("windowAr");
        assertEquals(1, w1.size(), "3/31 应收右闭落入第 1 期");
        assertEquals("AR-CTD-AR-W1", w1.get(0).get("AR_NO"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> w2 = (List<Map<String, Object>>) plans.get(1).get("windowAr");
        assertEquals(1, w2.size(), "4/1 应收左开落入第 2 期");
        assertEquals("AR-CTD-AR-W2", w2.get(0).get("AR_NO"));
        // 未落窗：7/1 晚于最后一期到期日 → 单列
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unw = (List<Map<String, Object>>) out.get("unwindowedAr");
        assertEquals(1, unw.size());
        assertEquals(0, new BigDecimal("5000")
                .compareTo((BigDecimal) out.get("unwindowedAmount")), "未落窗 5000");
    }

    @Test
    void deliveryEmptySoContract() {
        String id = seedContract("ctd-12", "SIGNED");
        Map<String, Object> out = service.deliveryProgress(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("soRows");
        assertEquals(0, rows.size(), "零 SO 空态");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals("0", summary.get("deliveryRatePct"), "零 SO 完成率 0%");
    }

    // ================================================================
    // 造数 / 清理
    // ================================================================

    private String seedContract(String id, String status) {
        jdbc.update("INSERT INTO erp_crm_contract (ID, CONTRACT_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "TITLE, AMOUNT, STATUS, VERSION_NO, SO_COUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, 'CTD 收口客户', 'CTD 收口合同', 100000, ?, 1, 0, 'junit', NOW(), '0', 0)",
                id, "CTD-" + id.toUpperCase(), CUST, status);
        return id;
    }

    private void seedSo(String id, String contractId, String status) {
        jdbc.update("INSERT INTO erp_sd_so (ID, SO_NO, CONTRACT_ID, CONTRACT_NO, CUSTOMER_ID, "
                + "TOTAL_AMOUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, 50000, ?, 'junit', NOW(), '0', 0)",
                id, "SO-" + id.toUpperCase(), contractId, "CTD-" + contractId.toUpperCase(),
                CUST, status);
        jdbc.update("UPDATE erp_crm_contract SET SO_COUNT = SO_COUNT + 1 WHERE ID=?", contractId);
    }

    /** 发货行（金额聚合口径：shipment_line.AMOUNT） */
    private void seedShipmentLine(String soId, String shipId, BigDecimal amount) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, STATUS, TOTAL_AMT, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'STANDARD', ?, 'SHIPPED', ?, "
                + "'junit', NOW(), '0', 0)", shipId, "SHP-" + shipId.toUpperCase(), CUST, amount);
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_NO, SO_LINE_ID, "
                + "ITEM_CODE, ITEM_NAME, QTY, UNIT_PRICE, AMOUNT, LINE_STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 1, ?, ?, ?, 'CTD-ITEM', 'CTD 发货物料', 1, ?, ?, 'SHIPPED', 'junit', NOW(), '0', 0)",
                shipId + "-L1", shipId, soId, "SO-" + soId.toUpperCase(), soId + "-SL1", amount, amount);
    }

    /** 应收（默认入账日=今天；可指定落窗日期） */
    private void seedAr(String id, String soId, BigDecimal amount, BigDecimal paid) {
        seedAr(id, soId, amount, paid, null);
    }

    private void seedAr(String id, String soId, BigDecimal amount, BigDecimal paid, String invoiceDate) {
        jdbc.update("INSERT INTO erp_fin_ar_invoice (ID, AR_NO, CUSTOMER_ID, SO_ID, SO_NO, "
                + "INVOICE_DATE, DUE_DATE, AMOUNT, PAID_AMOUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, COALESCE(?, CURDATE()), DATE_ADD(COALESCE(?, CURDATE()), INTERVAL 30 DAY), "
                + "?, ?, 'CONFIRMED', 'junit', COALESCE(?, NOW()), '0', 0)",
                id, "AR-" + id.toUpperCase(), CUST, soId, "SO-" + soId.toUpperCase(),
                invoiceDate, invoiceDate, amount, paid, invoiceDate);
    }

    /** 收款期次 */
    private void seedPlan(String id, String contractId, int periodNo, BigDecimal amount, String dueDate) {
        jdbc.update("INSERT INTO erp_crm_contract_plan (ID, CONTRACT_ID, CONTRACT_NO, PERIOD_NO, "
                + "PLAN_AMOUNT, DUE_DATE, PAID_AMOUNT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, 'junit', NOW(), '0', 0)",
                id, contractId, "CTD-" + contractId.toUpperCase(), periodNo, amount, dueDate);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_crm_contract_version WHERE CONTRACT_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD-%') t)");
        jdbc.update("DELETE FROM erp_crm_contract_plan WHERE CONTRACT_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD-%') t)");
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE SO_NO LIKE 'SO-CTD-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE SHIP_NO LIKE 'SHP-CTD-%'");
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE SO_NO LIKE 'SO-CTD-%'");
        jdbc.update("DELETE FROM erp_sd_so WHERE CONTRACT_NO LIKE 'CTD-%'");
        jdbc.update("DELETE FROM erp_crm_contract WHERE CONTRACT_NO LIKE 'CTD-%'");
        jdbc.update("DELETE FROM erp_mdm_customer_group WHERE ID='ctd-cust-1'");
    }

    private void asMgr() {
        asRole("ROLE_SALES_MGR");
    }

    private void asRole(String... roles) {
        // TestingAuthenticationToken(Object, Object, String...) varargs 构造器直接传角色串
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("ctd-tester", "x", roles));
    }
}

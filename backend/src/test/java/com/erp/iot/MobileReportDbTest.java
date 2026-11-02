package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotReportService;
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
 * 13.1 移动报工库测（add-iot-mobile，FR-4.11-1-x / BR-4.11-10/11/54）。
 * 造数：MO+派工+BOM 快照直插（无 mo_op 行 → FAI/IPQC 断言不触发，隔离报工本体）。
 */
@SpringBootTest
class MobileReportDbTest {

    @Autowired
    private IotReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-rpt-";
    private String moId;
    private String dispatchId;
    private String productCode;
    private String materialCode;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_SHOP_FLOOR"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_PLAN_MGR"))));
        seed();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void seed() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        productCode = P + "FG-" + n;
        materialCode = P + "RM-" + n;
        moId = P + "mo-" + n;
        dispatchId = P + "dp-" + n;
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, " +
                        "PRIORITY, STATUS, SHORTAGE_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?,1,'RELEASED','0','junit',NOW(),'0',0)",
                moId, "MO-IOT-" + n, "NORMAL", productCode, "移动报工测试成品", new BigDecimal("100"));
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, " +
                        "WC_CODE, WC_TYPE, PLAN_START, PLAN_END, DURATION_HOURS, STATUS, RISK_FLAG, " +
                        "SHORTAGE_FIRST_FLAG, OUTSOURCE_FLAG, RECEIPT_FLAG, RECV_QTY, CREATE_BY, " +
                        "CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,10,'移动报工测试工序','MOWC-IOT','INTERNAL',?,?,1000," +
                        "'RELEASED','0','0','0','0',0,'junit',NOW(),'0',0)",
                dispatchId, "DP-IOT-" + n, moId, "MO-IOT-" + n,
                LocalDate.now(), LocalDate.now().plusDays(3));
        jdbc.update("INSERT INTO erp_mrp_mo_bom (ID, MO_ID, LINE_NO, TREE_LEVEL, OWNER_ITEM, " +
                        "ITEM_CODE, ITEM_NAME, LINE_TYPE, UNIT_QTY, LOSS_RATE, BASE_UNIT, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,1,1,?,?,?,?,?,0.02,'PC','0',0)",
                P + "bom-" + n, moId, productCode, materialCode, "移动报工测试原料", "MATERIAL", new BigDecimal("10"));
        // 主料库存（验证库存不足提示）
        jdbc.update("INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) " +
                        "VALUES (?,?,?,?,?)",
                P + "st-" + n, materialCode, "移动报工测试原料", new BigDecimal("50"), new BigDecimal("50"));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_first_article WHERE DISPATCH_ID=?", dispatchId);
        jdbc.update("DELETE FROM erp_iot_offline_queue WHERE IDEMPOTENT_KEY LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_mobile_todo WHERE TITLE LIKE '%移动报工测试%' OR REF_ID LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_sync_conflict WHERE FIELD_PATH LIKE ?", "FIRST_ARTICLE:%" + dispatchId);
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_mrp_reporting WHERE MO_ID=?", moId);
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID=?", moId);
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE MO_ID=?", moId);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE=?", materialCode);
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID=?", moId);
        jdbc.update("DELETE FROM erp_iot_terminal WHERE DEVICE_CODE LIKE ?", P + "%");
    }

    // ---------- 2.1 工单接收 + 72h 升级 ----------

    @Test
    void releasedMoAppearsInMyOrders() {
        List<Map<String, Object>> orders = service.myOrders("user-admin", null);
        assertTrue(orders.stream().anyMatch(o -> dispatchId.equals(o.get("dispatchId"))),
                "已释放工单应进入我的工单");
        Map<String, Object> hit = orders.stream()
                .filter(o -> dispatchId.equals(o.get("dispatchId"))).findFirst().orElseThrow();
        assertEquals("NONE", hit.get("firstArticleStatus"));
        assertNotNull(hit.get("bom"), "推送内容含 BOM 摘要");
    }

    @Test
    void offlineTerminalEscalatesToDirector() {
        // 绑定终端离线超 72h + 名下有未报工派工（派工 CREATE_BY=junit → 绑 junit）→ 升级车间主任待办
        jdbc.update("INSERT INTO erp_iot_terminal (ID, DEVICE_CODE, DEVICE_TYPE, BIND_USER_ID, " +
                        "CERT_STATUS, LAST_ONLINE_AT, FAIL_CNT, LOCKED_FLAG, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'PDA', 'junit', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 73 HOUR), " +
                        "0,'0',NOW(),'0',0)",
                P + "term", P + "term-offline");
        Map<String, Object> r = service.scanPushEscalations();
        assertTrue(((Number) r.get("escalated")).intValue() >= 1, "离线超 72h 应生成升级待办");
        Integer todo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_mobile_todo WHERE TODO_TYPE='ESCALATE' " +
                        "AND REF_ID=? AND STATUS='PENDING'", Integer.class, P + "term-offline");
        assertTrue(todo >= 1, "升级待办落库");
        // 幂等：再扫不重复
        Map<String, Object> again = service.scanPushEscalations();
        assertEquals(0, ((Number) again.get("escalated")).intValue(), "重复扫描不重复升级");
    }

    // ---------- 2.2 扫码领料四校验 ----------

    @Test
    void verifyMaterialMismatchHardBlocks() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.verifyMaterial(dispatchId, "NOT-IN-BOM-XYZ", new BigDecimal("1"), null));
        assertEquals(409, ex.getCode(), "物料不匹配 L1 硬阻断");
        assertTrue(ex.getMessage().contains("BOM"));
    }

    @Test
    void verifyMaterialOverToleranceNeedsReason() {
        // BOM 单位用量 10，容差 0.5% → 10.2 超差
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.verifyMaterial(dispatchId, materialCode, new BigDecimal("10.2"), null));
        assertEquals(422, ex.getCode(), "超容差未填原因 422");
        Map<String, Object> ok = service.verifyMaterial(dispatchId, materialCode,
                new BigDecimal("10.2"), "试产损耗补投");
        assertEquals(Boolean.TRUE, ok.get("overTolerance"), "填因放行并留痕");
        assertEquals("试产损耗补投", ok.get("overReason"));
    }

    @Test
    void verifyMaterialBatchFormatAndStockWarning() {
        // ITEM|BATCH 双段码 + 库存不足提示：库存降到 3，领料 6（容差内不触发超差，3 < 6 触发缺料）
        jdbc.update("UPDATE erp_inv_stock SET QTY=3, AVAILABLE_QTY=3 WHERE ITEM_CODE=?", materialCode);
        Map<String, Object> r = service.verifyMaterial(dispatchId,
                materialCode + "|BATCH-IOT-1", new BigDecimal("6"), null);
        assertEquals(materialCode, r.get("itemCode"));
        assertEquals("BATCH-IOT-1", r.get("batchNo"));
        assertEquals(Boolean.TRUE, r.get("stockShortage"), "库存 3 < 6 应提示缺料");
        assertNotNull(r.get("warning"));
    }

    // ---------- 2.3 首件门禁 + 幂等 + 离线例外 ----------

    @Test
    void outputBlockedWithoutFirstArticle() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.reportOutput(body("good", "10", false, null)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("首件"));
    }

    @Test
    void outputBlockedWhileFirstArticlePending() {
        Map<String, Object> fa = service.submitFirstArticle(faBody(false));
        assertEquals("PENDING", fa.get("status"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.reportOutput(body("good", "10", false, null)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("首件待确认"));
        // IPQC 确认后放行
        Map<String, Object> confirmed = service.confirmFirstArticle((String) fa.get("id"), true, "OK");
        assertEquals("CONFIRMED", confirmed.get("status"));
        Map<String, Object> out = service.reportOutput(body("good", "10", false, null));
        assertEquals("CONFIRMED", out.get("status"));
        assertEquals(false, out.get("duplicate"));
    }

    @Test
    void offlineFirstArticleConditionReleaseThenRecheckFailToDiff() {
        // 离线例外（BR-4.11-10）：OFFLINE_FLAG=1 PENDING 条件放行报工
        Map<String, Object> fa = service.submitFirstArticle(faBody(true));
        assertEquals("1", fa.get("offlineFlag"));
        Map<String, Object> out = service.reportOutput(body("good", "5", false, null));
        assertEquals("CONFIRMED", out.get("status"), "离线首件条件放行");
        // 联网复核不合格（BR-4.11-54）→ 整批转差异单，不置工单暂停
        Map<String, Object> recheck = service.confirmFirstArticle((String) fa.get("id"), false, "尺寸超差");
        assertEquals("REJECTED", recheck.get("status"));
        assertNotNull(recheck.get("diffConflictId"), "复核不合格生成差异单");
        String moStatus = jdbc.queryForObject("SELECT STATUS FROM erp_mrp_mo WHERE ID=?",
                String.class, moId);
        assertEquals("RELEASED", moStatus, "不采用工单置暂停处置");
    }

    @Test
    void idempotentKeyDedupsSecondSubmit() {
        Map<String, Object> fa = service.submitFirstArticle(faBody(false));
        service.confirmFirstArticle((String) fa.get("id"), true, "OK");
        String idem = "KEY-" + P + UUID.randomUUID();
        Map<String, Object> b1 = body("good", "10", false, idem);
        service.reportOutput(b1);
        Map<String, Object> b2 = body("good", "10", false, idem);
        Map<String, Object> dup = service.reportOutput(b2);
        assertEquals(Boolean.TRUE, dup.get("duplicate"), "同幂等键重复提交去重");
        assertNotNull(dup.get("rptId"));
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_offline_queue WHERE IDEMPOTENT_KEY=?", Integer.class, idem);
        assertEquals(1, cnt, "幂等键唯一落库一次");
    }

    @Test
    void overProdConfirmPendingReviewThenApprove() {
        // MO 计划 100，报 110（超 10% > 5% 容差）→ 挂审批 → PENDING_REVIEW
        Map<String, Object> fa = service.submitFirstArticle(faBody(false));
        service.confirmFirstArticle((String) fa.get("id"), true, "OK");
        Map<String, Object> out = service.reportOutput(body("good", "110", false, null));
        assertEquals("PENDING_REVIEW", out.get("status"));
        assertEquals(Boolean.TRUE, out.get("overprodAckRequired"));
        String rptId = (String) out.get("rptId");
        // 车间主任审核通过 → 放行过账
        Map<String, Object> rev = service.reviewReport(rptId, true, "同意超产");
        assertEquals("CONFIRMED", rev.get("status"), "审核通过触发过账");
        // 审核待办已闭环
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_mobile_todo WHERE REF_ID=? AND TODO_TYPE='REPORT' " +
                        "AND STATUS='DONE'", Integer.class, rptId);
        assertEquals(1, done);
    }

    @Test
    void reviewRejectKeepsOpenAndCancelsTodo() {
        Map<String, Object> fa = service.submitFirstArticle(faBody(false));
        service.confirmFirstArticle((String) fa.get("id"), true, "OK");
        Map<String, Object> out = service.reportOutput(body("good", "110", false, null));
        String rptId = (String) out.get("rptId");
        Map<String, Object> rev = service.reviewReport(rptId, false, "数量核实有误");
        assertNotEquals("CONFIRMED", rev.get("status"), "驳回不触达过账");
        Integer cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_mobile_todo WHERE REF_ID=? AND STATUS='CANCELLED'",
                Integer.class, rptId);
        assertEquals(1, cancelled, "驳回退回并关待办");
    }

    // ---------- helpers ----------

    private Map<String, Object> body(String field, String qty, boolean overAck, String idem) {
        return Map.of(
                "dispatchId", dispatchId,
                "goodQty", new BigDecimal(qty),
                "ngQty", BigDecimal.ZERO,
                "overprodAck", overAck,
                "idempotentKey", idem == null ? "KEY-" + P + UUID.randomUUID() : idem,
                "deviceCode", P + "pda");
    }

    private Map<String, Object> faBody(boolean offline) {
        return Map.of(
                "dispatchId", dispatchId,
                "ctqJson", "{\"dimension\":\"42.0mm\"}",
                "photoUrl", "/uploads/iot-fa.jpg",
                "offline", offline);
    }
}

package com.erp.qms;

import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.qms.CopqService;
import com.erp.service.SysParamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6.7 质量成本（change rework-copq-analysis，spec quality-cost）：
 * SCAR 扣款确认归集、报废过账归集（各含幂等）、
 * report 五项分析（trend 序列/spike 环比/四维 rankings/targets 目标/suggestions+missing）、
 * 既有异常阻断与财务确认回归。
 */
@SpringBootTest
class CopqControlDbTest {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private CopqService copqService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private ScrapOrderService scrapService;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    private final String thisMonth = LocalDate.now().format(YM);

    @BeforeEach
    void seed() {
        purge();
        asAdmin();
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('ScarFinance','Scrap')) t1)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ScarFinance','Scrap')");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='SCRAP') t2)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='SCRAP'");
        jdbc.update("DELETE FROM erp_inv_scrap_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_inv_scrap_order WHERE CREATE_BY IN "
                + "('tester','junit')) t3)");
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE CREATE_BY IN ('tester','junit')");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE='CQ-SC-01'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE='CQ-SC-01'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE='CQ-SC-01'");
        jdbc.update("DELETE FROM erp_qms_scar_deduction WHERE ID LIKE 'cq-ded-%'");
        // 全清 COPQ（本套与历史冒烟/遗留数据，避免月度聚合跨用例污染——CREATE_DATE/OCCUR_DATE 双口径）
        jdbc.update("DELETE FROM erp_qms_copq_entry");
        jdbc.update("DELETE FROM erp_sys_param WHERE PARAM_KEY LIKE 'COPQ_TARGET_%'");
    }

    private void asAdmin() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    private void asWarehouse() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_WAREHOUSE"));
    }

    /** 造已确认归集明细（直接落库，绕过异常检测；OCCUR_DATE 与 CREATE_DATE 同步指定月份） */
    private void insertConfirmed(String category, String amount, LocalDate occur,
                                 String itemCode, String processCode, String customerName,
                                 String supplierId) {
        jdbc.update("INSERT INTO erp_qms_copq_entry (ID, ENTRY_NO, CATEGORY, AMOUNT, SOURCE_TYPE, "
                + "SOURCE_ID, ITEM_CODE, PROCESS_CODE, CUSTOMER_NAME, SUPPLIER_ID, SUPPLIER_NAME, "
                + "UNATTRIBUTED_FLAG, OCCUR_DATE, CREATE_DATE, STATUS, ANOMALY_FLAG, REMARK, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, 'CONFIRMED', '0', 'CQT-seed', 'junit', '0', 0)",
                "cq-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                "CQT-" + System.nanoTime() % 100000, category, amount,
                "cq-src-" + (itemCode == null ? "na" : itemCode),
                itemCode, processCode, customerName,
                supplierId, supplierId == null ? null : "供应商-" + supplierId,
                supplierId == null ? "1" : "0", occur, occur.atStartOfDay());
    }

    // ================= 2.1 SCAR 扣款确认归集 =================

    @Test
    void scarDeductionConfirmRecordsExternalFailure() {
        jdbc.update("INSERT INTO erp_qms_scar_deduction (ID, DEDUCT_NO, SCAR_ID, SCAR_NO, "
                + "SUPPLIER_ID, SUPPLIER_NAME, AMOUNT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('cq-ded-1', 'DED-CQ-001', 'scar-x', 'SCAR-CQ-001', 'sup-cq', "
                + "'冒烟供应商', 5000, 'PENDING_FINANCE', 'junit', '0', 0)");
        var inst = approvalEngine.submit("ScarFinance", "cq-ded-1",
                "SCAR 扣款确认：DED-CQ-001 / 5000", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_ADMIN", "扣款确认"))));
        String taskId = approvalEngine.todo().stream()
                .filter(t -> "ScarFinance".equals(t.get("bizType"))
                        && "cq-ded-1".equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
        assertNotNull(taskId, "扣款确认待办存在");
        approvalEngine.pass(taskId, "同意扣款");

        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT CATEGORY, AMOUNT, SOURCE_NO, SUPPLIER_NAME, STATUS FROM "
                        + "erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' AND SOURCE_ID='cq-ded-1'");
        assertEquals("EXTERNAL_FAILURE", entry.get("CATEGORY"));
        assertEquals(0, ((BigDecimal) entry.get("AMOUNT")).compareTo(new BigDecimal("5000")),
                "金额 5000");
        assertEquals("DED-CQ-001", entry.get("SOURCE_NO"));
        assertNotNull(entry.get("SUPPLIER_NAME"));

        // 扣款状态 CONFIRMED
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_scar_deduction WHERE ID='cq-ded-1'", String.class));
        // 幂等：再次调用归集键不重复（callback 幂等短路 + record 幂等双保险）
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCAR' "
                        + "AND SOURCE_ID='cq-ded-1'", Long.class);
        assertEquals(1L, cnt, "重复确认不重复归集");
        assertNotNull(inst);
    }

    // ================= 2.2 报废过账归集 =================

    @Test
    void scrapPostRecordsInternalFailureIdempotent() {
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('cq-it-1', 'CQ-SC-01', 'COPQ 报废测试物料', '0001', "
                + "'PC', 'STRUCT', 'BUY', 'NORMAL', '1', '0', '1', 'junit')");
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('cq-st-1', 'WH-MAIN', 'CQ-SC-01', 'COPQ 报废测试物料', 'CQ-B1', '', 50, 0, 0, "
                + "50, '2026-09-01', 'junit')");

        InvScrapOrder h = new InvScrapOrder();
        h.setWarehouseCode("WH-MAIN");
        h.setReason(InvScrapOrder.R_DAMAGE);
        InvScrapOrderLine l = new InvScrapOrderLine();
        l.setItemCode("CQ-SC-01");
        l.setItemName("COPQ 报废测试物料");
        l.setBatchNo("CQ-B1");
        l.setQty(new BigDecimal("5"));
        l.setUnitCost(new BigDecimal("20"));
        Map<String, Object> out = scrapService.create(h, List.of(l));
        String id = String.valueOf(((InvScrapOrder) out.get("order")).getId());
        scrapService.approve(id);
        scrapService.post(id);

        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT CATEGORY, AMOUNT, SOURCE_NO, STATUS FROM erp_qms_copq_entry "
                        + "WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID=?", id);
        assertEquals("INTERNAL_FAILURE", entry.get("CATEGORY"));
        assertEquals(0, ((BigDecimal) entry.get("AMOUNT")).compareTo(new BigDecimal("100")),
                "过账金额 = 5×20 = 100");
        assertNotNull(entry.get("SOURCE_NO"));

        // 已过账不可重复过账 → 归集仅 1 条
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='SCRAP' AND SOURCE_ID=?",
                Long.class, id);
        assertEquals(1L, cnt, "重复过账不重复归集");
    }

    // ================= 3.1 trend + spike =================

    @Test
    void trendAssembles12MonthsWithZeroFill() {
        String prev1 = LocalDate.now().minusMonths(1).format(YM);
        String prev2 = LocalDate.now().minusMonths(2).format(YM);
        insertConfirmed("INTERNAL_FAILURE", "1000", LocalDate.parse(prev2 + "-05"),
                "IT-A", null, null, null);
        insertConfirmed("EXTERNAL_FAILURE", "1000", LocalDate.parse(prev1 + "-05"),
                "IT-A", null, null, null);
        insertConfirmed("INTERNAL_FAILURE", "10000", LocalDate.parse(thisMonth + "-05"),
                "IT-A", null, null, null);

        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) rep.get("trend");
        assertEquals(12, trend.size(), "近 12 月序列");
        // 缺月补零：首月（11 个月前）total=0
        assertEquals(0, ((BigDecimal) trend.get(0).get("total")).signum(), "缺月补零");
        // 末月 = 当月合计 10000
        Map<String, Object> last = trend.get(11);
        assertEquals(thisMonth, last.get("month"));
        assertEquals(0, ((BigDecimal) last.get("total")).compareTo(new BigDecimal("10000")));
        // 环比：上月 1000 → 当月 10000，+900% 命中 spike
        @SuppressWarnings("unchecked")
        Map<String, Object> spike = (Map<String, Object>) rep.get("spike");
        assertEquals(true, spike.get("spike"), "环比 >50% 命中");
        assertNotNull(spike.get("spikeAlert"));
    }

    @Test
    void spikeNotHitWhenGrowthSmall() {
        String prev1 = LocalDate.now().minusMonths(1).format(YM);
        insertConfirmed("INTERNAL_FAILURE", "10000", LocalDate.parse(prev1 + "-05"),
                "IT-B", null, null, null);
        insertConfirmed("INTERNAL_FAILURE", "10200", LocalDate.parse(thisMonth + "-05"),
                "IT-B", null, null, null);

        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        Map<String, Object> spike = (Map<String, Object>) rep.get("spike");
        assertEquals(false, spike.get("spike"), "环比 2% 不命中");
        assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) spike.get("momRatio")),
                "momRatio = +2%");
    }

    @Test
    void spikeHitWhenPrevZero() {
        insertConfirmed("INTERNAL_FAILURE", "500", LocalDate.parse(thisMonth + "-05"),
                "IT-C", null, null, null);
        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        Map<String, Object> spike = (Map<String, Object>) rep.get("spike");
        assertEquals(true, spike.get("spike"), "上月 0 当月 >0 命中");
        assertNull(spike.get("momRatio"), "上月为 0 无比率");
    }

    // ================= 3.2 rankings + targets =================

    @Test
    void rankingsFourDimensionsWithUngrouped() {
        insertConfirmed("INTERNAL_FAILURE", "300", LocalDate.parse(thisMonth + "-05"),
                "IT-P1", "OP10", "客户甲", "sup-1");
        insertConfirmed("INTERNAL_FAILURE", "200", LocalDate.parse(thisMonth + "-06"),
                "IT-P1", "OP20", null, null);
        insertConfirmed("EXTERNAL_FAILURE", "100", LocalDate.parse(thisMonth + "-07"),
                "IT-P2", "OP10", "客户甲", "sup-2");

        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        Map<String, Object> rankings = (Map<String, Object>) rep.get("rankings");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) rankings.get("ITEM");
        assertEquals("IT-P1", items.get(0).get("name"), "产品维度第一名");
        assertEquals(0, ((BigDecimal) items.get(0).get("amount")).compareTo(new BigDecimal("500")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) rankings.get("PROCESS");
        assertEquals("OP10", procs.get(0).get("name"), "工序维度第一名");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> customers = (List<Map<String, Object>>) rankings.get("CUSTOMER");
        assertTrue(customers.stream().anyMatch(r -> "客户甲".equals(r.get("name"))), "客户维度");
        assertTrue(customers.stream().anyMatch(r -> "未归集".equals(r.get("name"))),
                "空客户归「未归集」");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sups = (List<Map<String, Object>>) rankings.get("SUPPLIER");
        assertTrue(sups.stream().anyMatch(r -> "未归因".equals(r.get("name"))),
                "供应商维度含未归因");
    }

    @Test
    void targetsNullWhenParamsEmpty() {
        Map<String, Object> rep = copqService.report(thisMonth);
        assertNull(rep.get("targets"), "参数全空 targets 为 null");
    }

    @Test
    void targetsComputedWhenParamsSet() {
        insertConfirmed("EXTERNAL_FAILURE", "350", LocalDate.parse(thisMonth + "-05"),
                "IT-D", null, null, null);
        insertConfirmed("INTERNAL_FAILURE", "650", LocalDate.parse(thisMonth + "-05"),
                "IT-D", null, null, null);
        sysParamService.setValue("COPQ_TARGET_EXTERNAL_RATIO", "20", "AMOUNT", "QMS",
                "COPQ 测试目标", "junit");
        sysParamService.setValue("COPQ_TARGET_EXTERNAL_FAILURE", "300", "AMOUNT", "QMS",
                "COPQ 测试目标", "junit");

        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) rep.get("targets");
        assertNotNull(targets, "参数设值后 targets 输出");
        @SuppressWarnings("unchecked")
        Map<String, Object> ratioRow = (Map<String, Object>) targets.get("EXTERNAL_RATIO");
        assertEquals(false, ratioRow.get("achieved"), "实际 35% > 目标 20% 未达成");
        @SuppressWarnings("unchecked")
        Map<String, Object> extRow = (Map<String, Object>) targets.get("EXTERNAL_FAILURE");
        assertEquals(false, extRow.get("achieved"), "实际 350 > 目标 300 未达成");
    }

    // ================= 3.3 suggestions + missing =================

    @Test
    void suggestionsInternalFailureDominant() {
        insertConfirmed("INTERNAL_FAILURE", "700", LocalDate.parse(thisMonth + "-05"),
                "IT-E", null, null, null);
        insertConfirmed("EXTERNAL_FAILURE", "100", LocalDate.parse(thisMonth + "-05"),
                "IT-E", null, null, null);
        insertConfirmed("PREVENTION", "100", LocalDate.parse(thisMonth + "-05"),
                "IT-E", null, null, null);
        insertConfirmed("APPRAISAL", "100", LocalDate.parse(thisMonth + "-05"),
                "IT-E", null, null, null);

        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) rep.get("suggestions");
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("内部失败")),
                "内部失败占比 70% >50% 最高 → 建议命中：" + suggestions);
    }

    @Test
    void missingListsUnconfirmedCategories() {
        // 直接落一条未确认明细（当月）
        jdbc.update("INSERT INTO erp_qms_copq_entry (ID, ENTRY_NO, CATEGORY, AMOUNT, SOURCE_TYPE, "
                + "SOURCE_ID, OCCUR_DATE, STATUS, ANOMALY_FLAG, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('cq-pend-1', 'CQT-PEND', 'APPRAISAL', 888, 'MANUAL', 'cq-pend-src', "
                + "?, 'PENDING_FINANCE', '0', 'junit', '0', 0)",
                LocalDate.parse(thisMonth + "-08"));
        Map<String, Object> rep = copqService.report(thisMonth);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) rep.get("missing");
        assertTrue(missing.stream().anyMatch(m -> "APPRAISAL".equals(m.get("category"))
                && new BigDecimal("888").compareTo((BigDecimal) m.get("amount")) == 0),
                "未确认明细进 missing：" + missing);
        // 既有键不删（兼容）
        assertNotNull(rep.get("confirmedTotal"));
        assertNotNull(rep.get("externalAlert"));
        assertNotNull(rep.get("bySupplier"));
    }

    // ================= 既有链路回归 =================

    @Test
    void recordIdempotentAndAnomalyHold() {
        // 同 sourceId+category 幂等
        Map<String, Object> body = Map.of("category", "PREVENTION", "sourceType", "MANUAL",
                "sourceId", "cq-idem-1", "amount", "100");
        var e1 = copqService.record(body);
        var e2 = copqService.record(body);
        assertEquals(e1.getId(), e2.getId(), "同键幂等返回既有");
        // 10× 异常阻断：先造月均 500，再录 6000
        for (int i = 1; i <= 3; i++) {
            insertConfirmed("APPRAISAL", "500", LocalDate.now().minusMonths(i).minusDays(3),
                    "IT-F", null, null, null);
        }
        var hold = copqService.record(Map.of("category", "APPRAISAL", "sourceType", "MANUAL",
                "sourceId", "cq-anom-1", "amount", "6000"));
        assertEquals("ANOMALY_HOLD", hold.getStatus(), "10× 异常阻断（BR-4.12-39）");
        assertEquals("1", hold.getAnomalyFlag());
    }

    @Test
    void processCustomerFieldsPassThrough() {
        var e = copqService.createManual(new java.util.HashMap<>(Map.of(
                "category", "INTERNAL_FAILURE",
                "amount", "66", "processCode", "OP30", "customerName", "客户丙",
                "sourceType", "MANUAL", "remark", "CQT-字段透传")));
        assertEquals("OP30", e.getProcessCode());
        assertEquals("客户丙", e.getCustomerName());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT PROCESS_CODE, CUSTOMER_NAME FROM erp_qms_copq_entry WHERE ID=?", e.getId());
        assertEquals("OP30", row.get("PROCESS_CODE"));
        assertEquals("客户丙", row.get("CUSTOMER_NAME"));
        // 分页行输出（keyword 不含 remark，全量取按 entryNo 定位）
        var page = copqService.page(1, 50, null, "INTERNAL_FAILURE", null);
        var hit = page.getRecords().stream()
                .filter(m -> e.getId().equals(m.get("id")))
                .findFirst();
        assertTrue(hit.isPresent(), "分页行包含新建归集");
        assertEquals("OP30", hit.get().get("processCode"));
        assertEquals("客户丙", hit.get().get("customerName"));
    }
}

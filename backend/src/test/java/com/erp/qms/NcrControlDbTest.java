package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.LotItem;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.qms.NcrService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6.5 不合格品控制（change rework-ncr-control，spec ncr-management）：
 * 隔离确认留痕不改状态机、CTQ 处置审批（提交不流转/通过流转/驳回改选）、
 * 挑选结构化校验（合计/低合格率软提示/成本归集）、处置阶段推导。
 */
@SpringBootTest
class NcrControlDbTest {

    @Autowired
    private NcrService ncrService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private StockPostingEngine stockEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asQualityEng();
        // 隔离确认用（冻结中 CREATED）
        insertNcr("iso-ncr-1", "NCR-ISO-0001", "ISO-ITEM-01", "0", "1", "CREATED", null);
        // 已关闭不可隔离
        insertNcr("iso-ncr-2", "NCR-ISO-0002", "ISO-ITEM-02", "0", "1", "CLOSED", null);
        // CTQ 审批链（ctqFlag=1）
        insertNcr("ctq-ncr-1", "NCR-CTQ-0001", "CTQ-ITEM-01", "1", "0", "CREATED", null);
        insertNcr("ctq-ncr-2", "NCR-CTQ-0002", "CTQ-ITEM-02", "1", "0", "CREATED", null);
        // 非 CTQ 直接流转回归
        insertNcr("ncr-plain-1", "NCR-PLN-0001", "PLN-ITEM-01", "0", "0", "CREATED", null);
        // 挑选结构化（已评审 SORTING + 方案）
        insertNcr("srt-ncr-1", "NCR-SRT-0001", "SRT-ITEM-01", "0", "0", "SORTING", "SORT");
        jdbc.update("UPDATE erp_qms_ncr SET DISPOSE_PLAN = '挑选范围：全检 A 区'"
                + " WHERE ID = 'srt-ncr-1'");
        insertNcr("srt-ncr-2", "NCR-SRT-0002", "SRT-ITEM-02", "0", "0", "SORTING", "SORT");
        jdbc.update("UPDATE erp_qms_ncr SET DISPOSE_PLAN = '挑选范围：B 区'"
                + " WHERE ID = 'srt-ncr-2'");
        // 返工结构化（已评审 REWORKING 无方案）
        insertNcr("rwk-ncr-1", "NCR-RWK-0001", "RWK-ITEM-01", "0", "0", "REWORKING", "REWORK");
        // 阶段推导五态
        insertNcr("stg-ret-1", "NCR-STG-0001", "STG-ITEM-01", "0", "0", "RETURNING", "RETURN");
        insertNcr("stg-con-1", "NCR-STG-0002", "STG-ITEM-02", "0", "0", "CONCESSION", "CONCESSION");
        insertNcr("stg-scr-1", "NCR-STG-0003", "STG-ITEM-03", "0", "0", "SCRAPPING", "SCRAP");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = 'WH-ISOT'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = 'WH-ISOT'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = 'ISO-BLK-01'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition'");
        jdbc.update("DELETE FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' "
                + "AND (SOURCE_ID LIKE 'iso-ncr-%' OR SOURCE_ID LIKE 'ctq-ncr-%' "
                + "OR SOURCE_ID LIKE 'ncr-plain-%' OR SOURCE_ID LIKE 'srt-ncr-%' "
                + "OR SOURCE_ID LIKE 'rwk-ncr-%' OR SOURCE_ID LIKE 'stg-%')");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='NCR' AND REF_ID LIKE 'srt-ncr-%'");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID LIKE 'iso-ncr-%' OR NCR_ID LIKE 'ctq-ncr-%' "
                + "OR NCR_ID LIKE 'ncr-plain-%' OR NCR_ID LIKE 'srt-ncr-%' OR NCR_ID LIKE 'rwk-ncr-%' "
                + "OR NCR_ID LIKE 'stg-%' OR NCR_ID = 'iso-blk-ncr' OR NCR_ID LIKE 'rep-ncr-%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'iso-ncr-%' OR ID LIKE 'ctq-ncr-%' "
                + "OR ID LIKE 'ncr-plain-%' OR ID LIKE 'srt-ncr-%' OR ID LIKE 'rwk-ncr-%' "
                + "OR ID LIKE 'stg-%' OR ID = 'iso-blk-ncr' OR ID LIKE 'rep-ncr-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT'");
        jdbc.update("DELETE FROM erp_qms_capa WHERE ID='capa-rpt-x'");
        // 漏检召回评估（6.1/6.2）数据
        jdbc.update("DELETE FROM erp_inv_trace_flow WHERE TRACE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_inv_trace_order WHERE ITEM_CODE='REL-ITEM-01') t1)");
        jdbc.update("DELETE FROM erp_inv_trace_log WHERE TRACE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_inv_trace_order WHERE ITEM_CODE='REL-ITEM-01') t2)");
        jdbc.update("DELETE FROM erp_inv_trace_order WHERE ITEM_CODE='REL-ITEM-01'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID IN ('lot-rel-1','lot-rel-2','lot-rel-3')");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE='REL-ITEM-01'");
        // 先删日志再删 NCR 行（反查锚点顺序）
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='RELEASED_LOT') t3) "
                + "OR NCR_ID = 'rel-rej-ncr'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE REF_TYPE='RELEASED_LOT' OR ID = 'rel-rej-ncr'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='NCR' AND (REF_ID LIKE 'rep-ncr-%' OR REF_ID LIKE 'cap-rpt-%')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ITEM_CODE IN ('REP-ITEM-01','REP-SUP-ITEM','CAP-ITEM')");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE IN ('REP-ITEM-01','REP-SUP-ITEM','CAP-ITEM')");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'rpt-sup-%' OR ID LIKE 'cap-rpt-%'");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID LIKE 'rpt-sup-%' OR NCR_ID LIKE 'cap-rpt-%'");
    }

    private void insertNcr(String id, String no, String item, String ctq, String regulatory,
                           String status, String disposition) {
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, QTY, STATUS, "
                + "SEVERITY, CTQ_FLAG, REGULATORY_FLAG, DISPOSITION, FROZEN_FLAG, FREEZE_SCOPE, "
                + "CREATE_BY) VALUES (?, ?, ?, 'NCR处置测试不合格', 50, ?, 'MAJOR', ?, ?, ?, '1', "
                + "'STOCK', 'junit')", id, no, item, status, ctq, regulatory, disposition);
    }

    private void asQualityEng() {
        login("ROLE_QUALITY_ENG");
    }

    private void login(String role) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }

    // ================= 隔离确认（6.5.1） =================

    @Test
    void isolateWritesColumnsAndLogWithoutStateChange() {
        Ncr n = ncrService.isolate("iso-ncr-1", "Q-HOLD-01");
        assertEquals("CREATED", n.getStatus(), "隔离确认不改状态机");
        assertEquals("1", n.getIsolatedFlag());
        assertEquals("Q-HOLD-01", n.getIsolateBin());
        assertNotNull(n.getIsolatedBy());
        assertNotNull(n.getIsolatedTime());
        Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='iso-ncr-1' AND ACTION='ISOLATE'",
                Integer.class);
        assertEquals(1, logCount, "ISOLATE 日志落库");
        // 重复确认覆盖留痕（日志 +1，列更新）
        ncrService.isolate("iso-ncr-1", "Q-HOLD-02");
        assertEquals("Q-HOLD-02", jdbc.queryForObject(
                "SELECT ISOLATE_BIN FROM erp_qms_ncr WHERE ID='iso-ncr-1'", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='iso-ncr-1' AND ACTION='ISOLATE'",
                Integer.class), "重复确认再次留痕");
    }

    @Test
    void isolateRejectsClosedAndMissingBin() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> ncrService.isolate("iso-ncr-2", "Q-HOLD-01"));
        assertEquals(422, e1.getCode(), "已关闭不可隔离");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> ncrService.isolate("iso-ncr-1", " "));
        assertEquals(422, e2.getCode(), "货位必填");
    }

    @Test
    void isolateForbiddenForPlanner() {
        login("ROLE_PLANNER");
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.isolate("iso-ncr-1", "Q-HOLD-01"));
        assertEquals(403, e.getCode(), "非质量侧隔离确认 403");
    }

    @Test
    void isolatedBatchBlockedFromIssueAndOutbound() {
        // 造物料 + 库存（指定批次出库路径）+ 隔离 NCR 关联同批次
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('it-iso-blk', 'ISO-BLK-01', '隔离阻断物料', "
                + "'0001', 'PCS', 'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit', '0')");
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('stk-iso-blk', 'WH-ISOT', 'ISO-BLK-01', '隔离阻断物料', 'ISO-B1', 'A-01', "
                + "100, 100, 'junit', '0', 0)");
        insertNcr("iso-blk-ncr", "NCR-ISOB-0001", "ISO-BLK-01", "0", "1", "CREATED", null);
        jdbc.update("UPDATE erp_qms_ncr SET BATCH_NO = 'ISO-B1' WHERE ID = 'iso-blk-ncr'");
        asQualityEng();
        ncrService.isolate("iso-blk-ncr", "Q-HOLD-01");

        // 指定批次出库 → 422 隔离阻断（与库存冻结并存的双保险）
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = "WH-ISOT";
        l.itemCode = "ISO-BLK-01";
        l.itemName = "隔离阻断物料";
        l.batchNo = "ISO-B1";
        l.qty = new BigDecimal("10");
        StockPostingEngine.Request req = StockPostingEngine.Request.of(
                "MATERIAL_OUT", "ISSUE", "ISO-OUT-" + System.nanoTime() % 100000, List.of(l));
        ServiceException e = assertThrows(ServiceException.class, () -> stockEngine.post(req));
        assertEquals(422, e.getCode(), "隔离批次出库 422");
        assertTrue(e.getMessage().contains("不合格隔离中"), e.getMessage());
        // 库存未动
        Map<String, Object> stock = jdbc.queryForMap(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ID = 'stk-iso-blk'");
        assertEquals(0, new BigDecimal(String.valueOf(stock.get("AVAILABLE_QTY")))
                .compareTo(new BigDecimal("100")), "库存未变动");

        // 缺省分配（不指定批次）→ 隔离批次剔池 → 库存不足 422（非隔离文案但阻断生效）
        StockPostingEngine.Line l2 = new StockPostingEngine.Line();
        l2.warehouseCode = "WH-ISOT";
        l2.itemCode = "ISO-BLK-01";
        l2.itemName = "隔离阻断物料";
        l2.qty = new BigDecimal("10");
        StockPostingEngine.Request req2 = StockPostingEngine.Request.of(
                "MATERIAL_OUT", "ISSUE", "ISO-OUT2-" + System.nanoTime() % 100000, List.of(l2));
        ServiceException e2 = assertThrows(ServiceException.class, () -> stockEngine.post(req2));
        assertEquals(422, e2.getCode(), "缺省分配隔离剔池后不足 422");
        assertTrue(e2.getMessage().contains("库存不足"), e2.getMessage());
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID = 'iso-blk-ncr'");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID = 'iso-blk-ncr'");
    }

    // ================= CTQ 处置审批 =================

    @Test
    void ctqReviewSubmitsApprovalWithoutTransition() {
        Ncr n = ncrService.review("ctq-ncr-1", "REWORK", "CTQ 单评审提交返工");
        assertEquals("CREATED", n.getStatus(), "CTQ 单提交不流转");
        assertEquals("1", n.getDispoPendingFlag(), "待审批标记");
        assertEquals("REWORK", n.getDisposition(), "处置暂存");
        var appr = approvalEngine.findByBiz("NcrDisposition", "ctq-ncr-1");
        assertNotNull(appr, "审批实例生成");
        assertEquals("PENDING", appr.getStatus(), "实例待签");
        // 重复提交 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.review("ctq-ncr-1", "RETURN", "还想改退货"));
        assertEquals(422, e.getCode(), "审批中不可重复提交");
        // 阶段推导：CTQ 审批中优先显示（即便状态为 CREATED 且不在我途五态）
        String stage = stageFromPage("ctq-ncr-1");
        assertEquals("处置待质量经理审批", stage, "CTQ 审批中阶段标识");
    }

    @Test
    void ctqApprovalPassTransitions() {
        ncrService.review("ctq-ncr-1", "REWORK", "CTQ 单提交返工待批");
        // 质量工程师视角：审批待办归质量经理，ENG 不可见
        String engView = approvalEngine.todo().stream()
                .filter(t -> "NcrDisposition".equals(t.get("bizType"))
                        && "ctq-ncr-1".equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
        assertNull(engView, "质量工程师不可签经理节点");
        // 质量经理签署
        login("ROLE_QUALITY_MGR");
        String taskId = approvalEngine.todo().stream()
                .filter(t -> "NcrDisposition".equals(t.get("bizType"))
                        && "ctq-ncr-1".equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
        assertNotNull(taskId, "经理待办存在");
        approvalEngine.pass(taskId, "同意返工");
        // detail 返回实体（非 map）——直接查库断言
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_qms_ncr WHERE ID='ctq-ncr-1'",
                String.class);
        assertEquals("REWORKING", st, "审批通过 → 按处置流转");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID='ctq-ncr-1' AND ACTION='REVIEW_APPROVE'",
                Integer.class), "审批通过日志");
        assertNull(jdbc.queryForObject(
                "SELECT DISPO_PENDING_FLAG FROM erp_qms_ncr WHERE ID='ctq-ncr-1'", String.class),
                "pending 标记清除");
    }

    @Test
    void ctqApprovalRejectAllowsReselect() {
        ncrService.review("ctq-ncr-2", "SCRAP", "CTQ 单提交报废待批");
        login("ROLE_QUALITY_MGR");
        String taskId = approvalEngine.todo().stream()
                .filter(t -> "NcrDisposition".equals(t.get("bizType"))
                        && "ctq-ncr-2".equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
        assertNotNull(taskId);
        approvalEngine.reject(taskId, "建议改选退货处置");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_qms_ncr WHERE ID='ctq-ncr-2'",
                String.class);
        assertEquals("CREATED", st, "驳回回 CREATED 可改选");
        assertNull(jdbc.queryForObject(
                "SELECT DISPOSITION FROM erp_qms_ncr WHERE ID='ctq-ncr-2'", String.class),
                "处置选择清除");
        // 改选重提（非让步/报废不触禁令）
        asQualityEng();
        Ncr n = ncrService.review("ctq-ncr-2", "RETURN", "按驳回意见改选退货");
        assertEquals("CREATED", n.getStatus(), "改选重提仍待审批");
        assertEquals("RETURN", n.getDisposition());
    }

    @Test
    void nonCtqReviewFlowsDirectly() {
        // 用 CONCESSION 验证直接流转（SORT/RETURN 会联动 SCAR/退货单，需完整供应商种子）
        Ncr n = ncrService.review("ncr-plain-1", "CONCESSION", "普通单直接评审让步");
        assertEquals("CONCESSION", n.getStatus(), "非 CTQ 直接流转（现状不变）");
        assertNull(jdbc.queryForObject(
                "SELECT DISPO_PENDING_FLAG FROM erp_qms_ncr WHERE ID='ncr-plain-1'", String.class),
                "非 CTQ 不产生审批标记");
        Long appr = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval WHERE BIZ_TYPE='NcrDisposition' "
                        + "AND BIZ_ID='ncr-plain-1'", Long.class);
        assertEquals(0L, appr, "非 CTQ 不生成审批单");
    }

    // ================= 返工结构化（planDisposition） =================

    @Test
    void reworkPlanRequiresStructuredFields() {
        // 缺结构化字段 → 422
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> ncrService.planDisposition("rwk-ncr-1", "返工方案概述", null, null, null, null));
        assertEquals(422, e1.getCode());
        assertTrue(e1.getMessage().contains("返工工序步骤"), e1.getMessage());
        // 结构化齐但未勾选 CTQ → 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> ncrService.planDisposition("rwk-ncr-1", "返工方案概述",
                        "拆解→换轴→复测", "扭矩 3.2±0.2N·m", "原 CTQ 全项 + 外观", false));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("CTQ"), e2.getMessage());
        // 齐全通过
        Ncr n = ncrService.planDisposition("rwk-ncr-1", "返工方案概述",
                "拆解→换轴→复测", "扭矩 3.2±0.2N·m", "原 CTQ 全项 + 外观", true);
        assertEquals("1", n.getReworkCtqConfirmed());
        assertNotNull(n.getReworkSteps());
    }

    // ================= 挑选结构化 + 成本（confirmDisposed） =================

    private void planSort(String id) {
        ncrService.planDisposition(id, "挑选范围确认（方案）");
    }

    @Test
    void sortConfirmRejectsUnbalancedQty() {
        planSort("srt-ncr-1");
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.confirmDisposed("srt-ncr-1", "挑选完成", null,
                        new BigDecimal("100"), new BigDecimal("70"), new BigDecimal("20"),
                        new BigDecimal("6")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("数量不平") || e.getMessage().contains("不平"),
                e.getMessage());
        // 状态未动
        assertEquals("SORTING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_ncr WHERE ID='srt-ncr-1'", String.class));
    }

    @Test
    void sortConfirmRejectsMissingStructured() {
        planSort("srt-ncr-1");
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.confirmDisposed("srt-ncr-1", "挑选完成"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("结构化"), e.getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    void sortConfirmLowYieldWarnsAndCostCollects() {
        planSort("srt-ncr-2");
        Map<String, Object> out = ncrService.confirmDisposed("srt-ncr-2", "挑选完成",
                new BigDecimal("3000"), new BigDecimal("100"), new BigDecimal("40"),
                new BigDecimal("60"), new BigDecimal("8"));
        Ncr n = (Ncr) out.get("ncr");
        List<String> warnings = (List<String>) out.get("warnings");
        assertEquals("DISPOSED", n.getStatus());
        assertNotNull(warnings);
        assertEquals(1, warnings.size(), "合格率 40% 软提示一条");
        assertTrue(warnings.get(0).contains("50%"), warnings.get(0));
        // 结构化落库
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SORT_TOTAL_QTY, SORT_OK_QTY, SORT_NG_QTY, SORT_HOURS, DISPOSE_COST_AMOUNT "
                        + "FROM erp_qms_ncr WHERE ID='srt-ncr-2'");
        assertEquals(0, new BigDecimal(String.valueOf(row.get("SORT_TOTAL_QTY")))
                .compareTo(new BigDecimal("100")));
        assertEquals(0, new BigDecimal(String.valueOf(row.get("SORT_OK_QTY")))
                .compareTo(new BigDecimal("40")));
        assertEquals(0, new BigDecimal(String.valueOf(row.get("DISPOSE_COST_AMOUNT")))
                .compareTo(new BigDecimal("3000")), "成本落库");
        // COPQ 归集可追溯
        Integer copq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' "
                        + "AND SOURCE_ID='srt-ncr-2' AND CATEGORY='INTERNAL_FAILURE'",
                Integer.class);
        assertEquals(1, copq, "COPQ 内部失败记录生成");
        // 复检批已生成（挑选完成后重新检验）
        Integer recheck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='NCR' "
                        + "AND REF_ID='srt-ncr-2' AND SOURCE_TYPE='RECHECK'", Integer.class);
        assertEquals(1, recheck, "复检批生成");
    }

    @Test
    void sortConfirmNormalYieldNoWarning() {
        planSort("srt-ncr-1");
        Map<String, Object> out = ncrService.confirmDisposed("srt-ncr-1", "挑选完成", null,
                new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("20"),
                new BigDecimal("5"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.get("warnings");
        assertEquals(0, warnings.size(), "合格率 80% 无软提示");
        assertEquals("DISPOSED", ((Ncr) out.get("ncr")).getStatus());
    }

    // ================= 处置阶段推导（6.5.2） =================

    private String stageFromPage(String ncrId) {
        for (long p = 1; p <= 5; p++) {
            var page = ncrService.page(p, 50, null, null, null, null, null, true);
            for (Map<String, Object> row : page.getRecords()) {
                if (ncrId.equals(row.get("id"))) {
                    return (String) row.get("disposeStage");
                }
            }
            if (p * 50 >= page.getTotal()) {
                break;
            }
        }
        return null;
    }

    @Test
    void disposeStageDerivedForFiveDispositions() {
        // 退货中（无退货单）→ 待退货出库
        assertEquals("待退货出库", stageFromPage("stg-ret-1"), "退货阶段");
        // 让步中（无 APPROVED 让步单）→ 待双签批准
        assertEquals("待双签批准", stageFromPage("stg-con-1"), "让步阶段");
        // 报废中（无报废单号）→ 待回填报废单号
        assertEquals("待回填报废单号", stageFromPage("stg-scr-1"), "报废阶段");
        // 挑选已评审批方案 → 待处置确认
        assertEquals("待处置确认", stageFromPage("srt-ncr-1"), "挑选阶段（已确认方案）");
        // 返工已评审无方案 → 待方案录入
        assertEquals("待方案录入", stageFromPage("rwk-ncr-1"), "返工阶段（未录方案）");
    }

    @Test
    void pageFrozenOnlyFiltersForIsolateView() {
        // 全部 seed 都是 FROZEN_FLAG=1；排除 CLOSED（iso-ncr-2）
        var page = ncrService.page(1, 100, null, null, null, null, true, null);
        List<String> ids = page.getRecords().stream().map(r -> String.valueOf(r.get("id"))).toList();
        assertTrue(ids.contains("iso-ncr-1"), "冻结中在列");
        assertTrue(!ids.contains("iso-ncr-2"), "CLOSED 不在隔离清单");
    }

    @Test
    void pageMultiStatusesFilter() {
        var page = ncrService.page(1, 100, null, null, "SORTING,REWORKING", null, null, null);
        List<String> ids = page.getRecords().stream().map(r -> String.valueOf(r.get("id"))).toList();
        assertTrue(ids.contains("srt-ncr-1") && ids.contains("rwk-ncr-1"), "多态过滤命中");
        assertTrue(!ids.contains("iso-ncr-1"), "非列举状态不出现");
    }

    // ================= 重复 NCR 升级链（4.1） =================

    /** 造最小检验批对象（同批复检链，refType=NCR 指向前单） */
    private InspectionLot recheckLot(String id, String lotNo, String batchNo, String priorNcrId) {
        InspectionLot l = new InspectionLot();
        l.setId(id);
        l.setLotNo(lotNo);
        l.setLotType("IQC");
        l.setItemCode("REP-ITEM-01");
        l.setItemName("重复升级测试物料");
        l.setBatchNo(batchNo);
        l.setLotQty(new BigDecimal("50"));
        l.setRiskGrade("C");
        l.setSupplierId("sup-rep");
        l.setSupplierName("重复升级测试供应商");
        l.setRefType("NCR");
        l.setRefId(priorNcrId);
        return l;
    }

    private List<LotItem> minorFailItems() {
        LotItem it = new LotItem();
        it.setCharacteristicName("外观");
        it.setCtqFlag("0");
        it.setRegulatoryFlag("0");
        it.setSpecType("LOOK");
        it.setJudge("FAIL");
        return List.of(it);
    }

    @Test
    void secondNcrUpgradesSeverityAndLinksPrior() {
        purge();
        // 前单：同批 MINOR 首次不合格（直接落库模拟历史）
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, BATCH_NO, "
                + "STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES "
                + "('rep-ncr-1', 'NCR-REP-0001', 'lot-rep-0', 'REP-ITEM-01', '重复升级测试物料', 50, "
                + "'REP-BATCH-1', 'CLOSED', 'MINOR', '0', '0', '0', 'NONE', 'junit')");
        // 复检批仍不合格 → 新 NCR 生成（缺陷 1 项 < Re 默认门槛 → 本应 MINOR）
        Ncr second = ncrService.createFromLot(
                recheckLot("lot-rep-1", "IL-REP-1", "REP-BATCH-1", "rep-ncr-1"), 1, 1, 5,
                minorFailItems());
        assertEquals("MAJOR", second.getSeverity(), "复检仍不合格 → 严重度上调一级");
        assertEquals("rep-ncr-1", second.getRefNcrId(), "关联前序 NCR");
        String basis = jdbc.queryForObject(
                "SELECT DETAIL FROM erp_qms_ncr_log WHERE NCR_ID=? AND ACTION='CREATE'",
                String.class, second.getId());
        assertTrue(basis.contains("上调"), "分级依据记录上调（C-4.12-16）：" + basis);
        // 首单无前单：不关联、不上调（MINOR 保持）
        Ncr first = ncrService.createFromLot(
                recheckLot("lot-rep-2", "IL-REP-2", "REP-BATCH-NEW", null), 1, 1, 5,
                minorFailItems());
        assertEquals("MINOR", first.getSeverity(), "首次不合格不上调");
        assertNull(first.getRefNcrId(), "首单无前序关联");
        // CRITICAL 封顶：CTQ 前单 + 复检 CTQ 仍 FAIL → 保持 CRITICAL 不越界
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, BATCH_NO, "
                + "STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES "
                + "('rep-ncr-2', 'NCR-REP-0002', 'lot-rep-9', 'REP-ITEM-01', '重复升级测试物料', 50, "
                + "'REP-BATCH-2', 'CLOSED', 'CRITICAL', '1', '0', '0', 'NONE', 'junit')");
        LotItem ctqFail = new LotItem();
        ctqFail.setCharacteristicName("CTQ 特性");
        ctqFail.setCtqFlag("1");
        ctqFail.setRegulatoryFlag("0");
        ctqFail.setSpecType("NUMERIC");
        ctqFail.setJudge("FAIL");
        Ncr capped = ncrService.createFromLot(
                recheckLot("lot-rep-3", "IL-REP-3", "REP-BATCH-2", "rep-ncr-2"), 1, 1, 5,
                List.of(ctqFail));
        assertEquals("CRITICAL", capped.getSeverity(), "Critical 封顶不上抛");
        assertEquals("rep-ncr-2", capped.getRefNcrId(), "封顶仍关联前单");
    }

    // ================= 三维度重复扫描（4.2） =================

    @Test
    void repeatNcrScanFiresCapaTodoOncePerWindow() {
        purge();
        // 同供应商 3 张（近 90 天内，MAJOR 各异批次）
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, "
                    + "SUPPLIER_ID, SUPPLIER_NAME, STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, "
                    + "FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES "
                    + "(?, ?, ?, 'REP-SUP-ITEM', '重复扫描物料', 10, 'sup-rep-x', '重复扫描供应商', "
                    + "'CLOSED', ?, '0', '0', '0', 'NONE', 'junit')",
                    "rpt-sup-" + i, "NCR-RPT-S" + i, "lot-rpt-s" + i,
                    i == 3 ? "MAJOR" : "MINOR");
        }
        // 首扫：供应商维度触发待办（MGR + SQE 两行）
        int first = ncrService.sweepRepeatNcrCapaTodos();
        assertTrue(first >= 1, "首扫触发至少一个维度待办，实际 " + first);
        Integer todoRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' "
                        + "AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-rep-x%'",
                Integer.class);
        assertTrue(todoRows >= 1, "供应商维度待办落库");
        String content = jdbc.queryForObject(
                "SELECT CONTENT FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' "
                        + "AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-rep-x%' ORDER BY CREATE_DATE DESC LIMIT 1",
                String.class);
        assertTrue(content.contains("NCR-RPT-S1") && content.contains("NCR-RPT-S3"),
                "待办关联全部 NCR 单号：" + content);
        assertTrue(content.contains("上调"), "严重度上调建议：" + content);
        Integer sqeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' "
                        + "AND TARGET_ROLE='ROLE_SQE' AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-rep-x%'",
                Integer.class);
        assertEquals(1, sqeRows, "供应商维度加推 SQE");
        // 重扫同窗口幂等：待办数不变
        ncrService.sweepRepeatNcrCapaTodos();
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' "
                        + "AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-rep-x%'",
                Integer.class);
        assertEquals(todoRows.intValue(), after, "重扫不重复生成待办");
    }

    @Test
    void repeatNcrScanSkipsDimensionWithOpenCapa() {
        purge();
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, "
                    + "SUPPLIER_ID, SUPPLIER_NAME, STATUS, SEVERITY, CTQ_FLAG, REGULATORY_FLAG, "
                    + "FROZEN_FLAG, FREEZE_SCOPE, CREATE_BY) VALUES "
                    + "(?, ?, ?, 'CAP-ITEM', '在途跳过物料', 10, 'sup-cap-x', '在途跳过供应商', "
                    + "'CLOSED', 'MAJOR', '0', '0', '0', 'NONE', 'junit')",
                    "cap-rpt-" + i, "NCR-CAPR" + i, "lot-cap-r" + i);
        }
        // 组内首张 NCR 挂未关闭 CAPA → 整维度跳过
        jdbc.update("INSERT INTO erp_qms_capa (ID, CAPA_NO, NCR_ID, NCR_NO, TITLE, "
                + "SOURCE_TYPE, SEVERITY, STATUS, CREATE_BY) VALUES "
                + "('capa-rpt-x', 'CAPA-RPT-001', 'cap-rpt-1', 'NCR-CAPR1', '在途 CAPA', "
                + "'REPEAT', 'MAJOR', 'OPEN', 'junit')");
        int fired = ncrService.sweepRepeatNcrCapaTodos();
        Integer todoRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_REPEAT' "
                        + "AND BIZ_ID LIKE 'CAPA_TODO:SUPPLIER:sup-cap-x%'",
                Integer.class);
        assertEquals(0, todoRows, "在途 CAPA 维度跳过（不生成待办）");
        // 其他维度（如物料维度 cap-rpt 组也挂了 CAPA 关联——同组同跳过），总 fired 不含该组
        assertTrue(fired >= 0, "扫描正常返回（实际 " + fired + "）");
        jdbc.update("DELETE FROM erp_qms_capa WHERE ID='capa-rpt-x'");
    }

    // ================= 漏检召回评估（6.1/6.2） =================

    private void insertReleasedLot(String lotId, String lotNo, String batchNo) {
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, BATCH_NO, STATUS, RESULT, RISK_GRADE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "(?, ?, 'IQC', 'REL-ITEM-01', '放行召回测试物料', 100, ?, 'RELEASED', 'PASS', "
                + "'C', 'junit', '0', 0)", lotId, lotNo, batchNo);
        // 批次台账（trace analyze 三索引归一需要）
        jdbc.update("INSERT IGNORE INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "(?, ?, 'REL-ITEM-01', '放行召回测试物料', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), "
                + "'junit', '0', 0)", "bt-" + batchNo, batchNo);
    }

    @Test
    void createForReleasedLotValidatesAndMarks() {
        purge();
        insertReleasedLot("lot-rel-1", "IL-REL-1", "REL-BATCH-1");
        // 未放行批 → 422
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, BATCH_NO, STATUS, RESULT, RISK_GRADE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('lot-rel-2', 'IL-REL-2', 'IQC', 'REL-ITEM-01', '放行召回测试物料', 10, "
                + "'REL-BATCH-2', 'INPUTTING', 'PENDING', 'C', 'junit', '0', 0)");
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.createForReleasedLot("lot-rel-2", "尺寸超差", "漏检", null, null));
        assertEquals(422, e.getCode(), "未放行批 422");
        assertTrue(e.getMessage().contains("未放行"), e.getMessage());

        // 放行批创建成功：标记放行后发现 + refType + 分级时限
        Ncr n = ncrService.createForReleasedLot("lot-rel-1", "外观漏检", "放行后客户投诉划伤", "MAJOR", null);
        assertEquals("RELEASED_LOT", n.getRefType(), "refType 标记");
        assertEquals("lot-rel-1", n.getRefId(), "refId = 批次");
        assertTrue(n.getDefectDesc().contains("放行后发现"), "标记放行后发现：" + n.getDefectDesc());
        assertNotNull(n.getReviewDueTime(), "评审分级时限起算");
        assertEquals("CREATED", n.getStatus());

        // 幂等：重复创建 422 返既有
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> ncrService.createForReleasedLot("lot-rel-1", "再报一次", "dup", null, null));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains(n.getNcrNo()), "返既有单号：" + e2.getMessage());
        // 关闭后可再建
        jdbc.update("UPDATE erp_qms_ncr SET STATUS='CLOSED' WHERE ID=?", n.getId());
        Ncr again = ncrService.createForReleasedLot("lot-rel-1", "再次发现", "closed 后再建", null, "1");
        assertEquals("1", again.getCtqFlag(), "ctqFlag 透传");
        assertNotEquals(n.getId(), again.getId(), "关闭后可重建新单");
    }

    @Test
    void recallEvaluationLinksTraceBothWays() {
        purge();
        insertReleasedLot("lot-rel-3", "IL-REL-3", "REL-BATCH-3");
        Ncr n = ncrService.createForReleasedLot("lot-rel-3", "放行后误判", "召回评估入口测试", null, null);
        // 发起评估 → 建追溯单 + 双向关联 + 不冻结
        Map<String, Object> out = ncrService.recallEvaluation(n.getId());
        assertEquals(false, out.get("existing"), "首建非既有");
        String traceId = String.valueOf(out.get("traceId"));
        assertNotNull(traceId);
        String srcNcr = jdbc.queryForObject(
                "SELECT SOURCE_NCR_ID FROM erp_inv_trace_order WHERE ID=?", String.class, traceId);
        assertEquals(n.getId(), srcNcr, "追溯单侧回写来源 NCR");
        String traceNo = String.valueOf(out.get("traceNo"));
        Integer logRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_ncr_log WHERE NCR_ID=? AND ACTION='RECALL_EVAL' "
                        + "AND DETAIL LIKE CONCAT('%', ?, '%')", Integer.class, n.getId(), traceNo);
        assertEquals(1, logRows, "NCR 日志承载追溯单号（详情可反查）");
        // 不代执行冻结：追溯单发起后无新增 NCR 库存冻结（本 NCR 冻结是建单时的，评估动作本身不动冻结）
        String freezeScope = jdbc.queryForObject(
                "SELECT FREEZE_SCOPE FROM erp_qms_ncr WHERE ID=?", String.class, n.getId());
        assertEquals("NONE", freezeScope, "无库存行时建单仅标记，评估不改冻结");
        // 重复点击 → 返回既有
        Map<String, Object> again = ncrService.recallEvaluation(n.getId());
        assertEquals(true, again.get("existing"), "重复入口返既有单");
        assertEquals(traceId, String.valueOf(again.get("traceId")), "同一追溯单");
        // 非 RELEASED_LOT 的 NCR 拒绝（自建普通单，避免依赖 seed 时序）
        insertNcr("rel-rej-ncr", "NCR-RELREJ-0001", "REL-REJ-ITEM", "0", "0", "CREATED", null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.recallEvaluation("rel-rej-ncr"));
        assertEquals(422, e.getCode(), "非放行后发现 NCR 422");
    }
}
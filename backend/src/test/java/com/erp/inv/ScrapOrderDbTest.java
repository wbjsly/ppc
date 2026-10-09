package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.ScrapOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报废单 DB 集成（真实 MySQL，spec scrap-order，任务 4.1~4.4）：
 * 创建校验与库龄固化、呆滞三方会签（C-4.4-14 过账阻断→签署→放行）、
 * 质量来源 NCR 处置分流（方案 a）、手工直批与单位成本门槛、两步凭证、序列门槛、权限。
 */
@SpringBootTest
class ScrapOrderDbTest {

    private static final String WH = "WH-MAIN";
    private static final String ITEM = "IT-SC-01";
    private static final String ITEM_SER = "IT-SC-SER";
    private static final String BATCH = "SC-B1";
    private static final String NCR_REWORK = "NCR-SC-0001";
    private static final String NCR_SCRAP = "NCR-SC-0002";

    @Autowired
    private ScrapOrderService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private GlVoucherService voucherService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('sc-it-1', ?, '报废测试物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', '1', '0', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('sc-it-ser', ?, '报废序列物料', '0001', 'PC', 'STRUCT', "
                + "'BUY', 'NORMAL', '1', '1', '1', 'junit')", ITEM_SER);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('sc-st-1', ?, ?, '报废测试物料', ?, '', 100, 0, 0, 100, '2026-09-01', 'junit')",
                WH, ITEM, BATCH);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('sc-st-2', ?, ?, '报废序列物料', 'SC-SER', '', 10, 0, 0, 10, '2026-09-10', 'junit')",
                WH, ITEM_SER);
        // NCR：处置=返工 / 处置=报废
        insertNcr(NCR_REWORK, ITEM, "REWORK");
        insertNcr(NCR_SCRAP, ITEM, "SCRAP");
    }
    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE = 'Scrap')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'Scrap'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE = 'SCRAP')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'SCRAP'");
        jdbc.update("DELETE FROM erp_inv_scrap_order_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM erp_inv_scrap_order WHERE CREATE_BY IN ('tester','junit') "
                + "OR CREATE_BY IS NULL)");
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE CREATE_BY IN ('tester','junit') "
                + "OR CREATE_BY IS NULL");
        jdbc.update("DELETE FROM erp_inv_serial WHERE ITEM_CODE LIKE 'IT-SC-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE 'IT-SC-%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE LIKE 'IT-SC-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-SC-%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE NCR_NO IN ('NCR-SC-0001', 'NCR-SC-0002') "
                + "OR ID LIKE 'sc-ncr-%'");
    }

    private void insertNcr(String id, String item, String disposition) {
        String no = "REWORK".equals(disposition) ? NCR_REWORK : NCR_SCRAP;
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, QTY, STATUS, "
                + "SEVERITY, DISPOSITION, CREATE_BY) VALUES (?, ?, ?, '报废测试物料', 20, "
                + "'REVIEWING', 'MAJOR', ?, 'junit')", id, no, item, disposition);
    }

    // ================= 4.1 创建校验与库龄固化 =================

    @Test
    void createValidationsAndAgeSnapshot() {
        // 原因取值域
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, "WRONG", null), List.of(line(ITEM, "10", "2"))));
        assertEquals(422, e1.getCode());
        // QUALITY 必填 NCR 号
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, InvScrapOrder.R_QUALITY, null), List.of(line(ITEM, "10", "2"))));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("NCR"));
        // QUALITY NCR 不存在
        InvScrapOrder h = head(WH, InvScrapOrder.R_QUALITY, "NCR-NOPE");
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.create(h, List.of(line(ITEM, "10", "2"))));
        assertEquals(422, e3.getCode());
        // 批次必填
        InvScrapOrderLine noBatch = line(ITEM, "10", "2");
        noBatch.setBatchNo("");
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, InvScrapOrder.R_DAMAGE, null), List.of(noBatch)));
        assertEquals(422, e4.getCode());
        // 超在手（100）
        ServiceException e5 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, InvScrapOrder.R_DAMAGE, null),
                        List.of(line(ITEM, "150", "2"))));
        assertEquals(422, e5.getCode());
        assertTrue(e5.getMessage().contains("超在手"), e5.getMessage());

        // 正常创建：SC 单号 + 库龄固化 + 合计
        Map<String, Object> out = service.create(head(WH, InvScrapOrder.R_STALE, null),
                List.of(line(ITEM, "50", "3.5")));
        @SuppressWarnings("unchecked")
        InvScrapOrder o = (InvScrapOrder) out.get("order");
        assertTrue(o.getScrapNo().startsWith("SC"), o.getScrapNo());
        assertEquals(InvScrapOrder.ST_DRAFT, o.getStatus());
        assertEquals(0, new BigDecimal("50").compareTo(o.getTotalQty()));
        assertEquals(0, new BigDecimal("175.00").compareTo(o.getTotalAmount()), "50×3.5");
        @SuppressWarnings("unchecked")
        List<InvScrapOrderLine> lines = (List<InvScrapOrderLine>) out.get("lines");
        int expectedAge = (int) ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 1), LocalDate.now());
        assertEquals(expectedAge, lines.get(0).getStockAgeDays(), "库龄快照=MIN(INBOUND_DATE) 距今");
    }

    // ================= 4.2 + 4.3 呆滞三方会签与过账（C-4.4-14） =================

    @Test
    void staleJointApprovalGateAndTwoStepVoucher() {
        String id = createOrder(InvScrapOrder.R_STALE, ITEM, BATCH, "10", "2");

        // 直批被拒（须会签）
        ServiceException e0 = assertThrows(ServiceException.class, () -> service.approve(id));
        assertEquals(422, e0.getCode());
        assertTrue(e0.getMessage().contains("三方会签"), e0.getMessage());

        // 未提交会签直接过账 → C-4.4-14
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.post(id));
        assertEquals(422, e1.getCode());
        assertTrue(e1.getMessage().contains("C-4.4-14"), e1.getMessage());

        // 提交会签 → 实例 + 3 JOINT 节点
        service.submitForApproval(id);
        @SuppressWarnings("unchecked")
        InvScrapOrder afterSubmit = (InvScrapOrder) service.detail(id).get("order");
        assertNotNull(afterSubmit.getApprId());
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, afterSubmit.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        assertEquals(3, tasks.size(), "技术/质量/财务三节点");

        // 会签中过账仍 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.post(id));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("C-4.4-14"), e2.getMessage());

        // 三方逐签（各自角色）→ 回调置 APPROVED
        for (ApprovalTask t : tasks) {
            asRole(t.getRoleRequired());
            approvalEngine.pass(t.getId(), "同意报废（" + t.getNodeName() + "）");
        }
        asWarehouse();
        @SuppressWarnings("unchecked")
        InvScrapOrder approved = (InvScrapOrder) service.detail(id).get("order");
        assertEquals(InvScrapOrder.ST_APPROVED, approved.getStatus(), "会签全过→APPROVED");

        // 过账：引擎扣减 + 凭证一
        Map<String, Object> posted = service.post(id);
        @SuppressWarnings("unchecked")
        InvScrapOrder po = (InvScrapOrder) posted.get("order");
        assertEquals(InvScrapOrder.ST_POSTED, po.getStatus());
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT QTY FROM erp_inv_stock WHERE ID = 'sc-st-1'", BigDecimal.class);
        assertEquals(0, new BigDecimal("90").compareTo(onHand), "库存扣减 10");
        List<Map<String, Object>> v1 = voucherService.listBySource("SCRAP", po.getScrapNo());
        assertEquals(1, v1.size(), "凭证一");
        // 重复过账 422
        ServiceException e3 = assertThrows(ServiceException.class, () -> service.post(id));
        assertEquals(422, e3.getCode());

        // 处置核销：凭证二 + DISPOSED；重复 422
        Map<String, Object> disposed = service.dispose(id);
        @SuppressWarnings("unchecked")
        InvScrapOrder di = (InvScrapOrder) disposed.get("order");
        assertEquals(InvScrapOrder.ST_DISPOSED, di.getStatus());
        List<Map<String, Object>> v2 = voucherService.listBySource("SCRAP", po.getScrapNo());
        assertEquals(2, v2.size(), "两步凭证成链");
        ServiceException e4 = assertThrows(ServiceException.class, () -> service.dispose(id));
        assertEquals(422, e4.getCode());
    }

    @Test
    void disposeBeforePostRejected() {
        String id = createOrder(InvScrapOrder.R_DAMAGE, ITEM, BATCH, "5", "2");
        service.approve(id);
        ServiceException e = assertThrows(ServiceException.class, () -> service.dispose(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("出库过账"), e.getMessage());
    }

    // ================= 4.3 质量来源分流（方案 a） =================

    @Test
    void qualityGateRequiresNcrScrapDisposition() {
        // 处置=返工 → 过账 422
        String id1 = createOrder(InvScrapOrder.R_QUALITY, ITEM, BATCH, "10", "2",
                NCR_REWORK);
        service.approve(id1);
        ServiceException e = assertThrows(ServiceException.class, () -> service.post(id1));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("NCR 处置非报废"), e.getMessage());
        assertEquals(InvScrapOrder.ST_APPROVED,
                ((InvScrapOrder) service.detail(id1).get("order")).getStatus(), "阻断后状态不变");

        // 处置=SCRAP → 放行（无会签实例）
        String id2 = createOrder(InvScrapOrder.R_QUALITY, ITEM, BATCH, "10", "2",
                NCR_SCRAP);
        service.approve(id2);
        Map<String, Object> out = service.post(id2);
        @SuppressWarnings("unchecked")
        InvScrapOrder o = (InvScrapOrder) out.get("order");
        assertEquals(InvScrapOrder.ST_POSTED, o.getStatus());
        assertNull(o.getApprId(), "质量来源不产生会签实例");
    }

    // ================= 4.3 手工直批 + 成本门槛 + 序列门槛 =================

    @Test
    void damageDirectApproveAndCostGate() {
        // 单位成本缺失 → 过账 422（凭证金额依据）
        InvScrapOrderLine noCost = line(ITEM, "10", "2");
        noCost.setUnitCost(null);
        Map<String, Object> out = service.create(head(WH, InvScrapOrder.R_DAMAGE, null),
                List.of(noCost));
        @SuppressWarnings("unchecked")
        InvScrapOrder o = (InvScrapOrder) out.get("order");
        service.approve(o.getId());
        ServiceException e = assertThrows(ServiceException.class, () -> service.post(o.getId()));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("单位成本"), e.getMessage());

        // 有成本 → 直批无会签实例、过账成功
        String id = createOrder(InvScrapOrder.R_OTHER, ITEM, BATCH, "5", "2");
        @SuppressWarnings("unchecked")
        InvScrapOrder o2 = (InvScrapOrder) service.approve(id).get("order");
        assertEquals(InvScrapOrder.ST_APPROVED, o2.getStatus());
        assertNull(o2.getApprId(), "手工报废无会签实例");
        @SuppressWarnings("unchecked")
        InvScrapOrder p = (InvScrapOrder) service.post(id).get("order");
        assertEquals(InvScrapOrder.ST_POSTED, p.getStatus());
    }

    @Test
    void serialItemRequiresUsableSerials() {
        // 序列物料未带序列 → 引擎 422（C-4.4-02）
        String id = createOrder(InvScrapOrder.R_DAMAGE, ITEM_SER, "SC-SER", "2", "5");
        service.approve(id);
        ServiceException e = assertThrows(ServiceException.class, () -> service.post(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("序列号"), e.getMessage());

        // 序列不在库 → 422 序列号不可用
        InvScrapOrderLine l = line(ITEM_SER, "2", "5");
        l.setBatchNo("SC-SER");
        l.setSerials("SN-MISSING-1");
        Map<String, Object> out = service.create(head(WH, InvScrapOrder.R_DAMAGE, null), List.of(l));
        @SuppressWarnings("unchecked")
        String id2 = ((InvScrapOrder) out.get("order")).getId();
        service.approve(id2);
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.post(id2));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("序列号不可用"), e2.getMessage());

        // 序列在库 → 过账成功
        jdbc.update("INSERT INTO erp_inv_serial (ID, SERIAL_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "STATUS, CREATE_BY) VALUES ('sc-ser-1', 'SN-OK-1', ?, '报废序列物料', 'SC-SER', "
                + "'IN_STOCK', 'junit')", ITEM_SER);
        InvScrapOrderLine l3 = line(ITEM_SER, "2", "5");
        l3.setBatchNo("SC-SER");
        l3.setSerials("SN-OK-1");
        Map<String, Object> out3 = service.create(head(WH, InvScrapOrder.R_DAMAGE, null), List.of(l3));
        @SuppressWarnings("unchecked")
        String id3 = ((InvScrapOrder) out3.get("order")).getId();
        service.approve(id3);
        @SuppressWarnings("unchecked")
        InvScrapOrder p3 = (InvScrapOrder) service.post(id3).get("order");
        assertEquals(InvScrapOrder.ST_POSTED, p3.getStatus(), "带可用序列过账成功");
    }

    // ================= 4.5 权限 =================

    @Test
    void permission401And403() {
        SecurityContextHolder.clearContext();
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, InvScrapOrder.R_DAMAGE, null), List.of(line(ITEM, "1", "1"))));
        assertEquals(401, e1.getCode());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", null, "ROLE_USER"));
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(head(WH, InvScrapOrder.R_DAMAGE, null), List.of(line(ITEM, "1", "1"))));
        assertEquals(403, e2.getCode());
    }

    // ---------- helpers ----------

    private String createOrder(String reason, String item, String batch, String qty, String price) {
        return createOrder(reason, item, batch, qty, price, null);
    }

    private String createOrder(String reason, String item, String batch, String qty,
                               String price, String ncrNo) {
        Map<String, Object> out = service.create(head(WH, reason, ncrNo),
                List.of(line(item, qty, price, batch)));
        @SuppressWarnings("unchecked")
        InvScrapOrder o = (InvScrapOrder) out.get("order");
        return o.getId();
    }

    private InvScrapOrder head(String wh, String reason, String ncrNo) {
        InvScrapOrder h = new InvScrapOrder();
        h.setWarehouseCode(wh);
        h.setReason(reason);
        h.setNcrNo(ncrNo);
        return h;
    }

    private InvScrapOrderLine line(String item, String qty, String price) {
        return line(item, qty, price, BATCH);
    }

    private InvScrapOrderLine line(String item, String qty, String price, String batch) {
        InvScrapOrderLine l = new InvScrapOrderLine();
        l.setItemCode(item);
        l.setItemName("报废测试物料");
        l.setBatchNo(batch);
        l.setQty(new BigDecimal(qty));
        l.setUnitCost(new BigDecimal(price));
        return l;
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }
}

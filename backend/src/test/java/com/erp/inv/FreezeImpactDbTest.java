package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.sd.Reservation;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.FreezeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结影响面快照 + 影响评估预估 DB 集成（spec freeze-management ADDED 需求 ②③，
 * change add-freeze-management-menus 任务 2.1/2.2/2.3）：
 * 执行冻结生成 SO/PO/工单三级快照（SO 精确、PO 物料级、工单数据源未落地占位）、
 * 无预留空清单、query 返回 impactJson、estimate 阈值三场景。
 */
@SpringBootTest
class FreezeImpactDbTest {

    private static final String WH = "WH-FIMP";
    private static final String ITEM = "IT-FIMP-TEST";
    private static final String BATCH = "B-FIMP-1";

    @Autowired
    private FreezeService freezeService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private InvFreezeDao freezeDao;
    @Autowired
    private ReservationDao reservationDao;
    @Autowired
    private PurchaseOrderDao poDao;
    @Autowired
    private PurchaseOrderLineDao poLineDao;
    @Autowired
    private ApprovalInstanceDao instanceDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper jsonMapper;

    private final List<String> freezeIds = new ArrayList<>();
    private final List<String> stockIds = new ArrayList<>();
    private final List<String> resIds = new ArrayList<>();
    private final List<String> poIds = new ArrayList<>();
    private final List<String> poLineIds = new ArrayList<>();
    private final List<String> apprIds = new ArrayList<>();

    @BeforeEach
    void purgeAndLogin() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID "
                + "WHERE a.BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE WAREHOUSE_CODE = ?", WH);
        login("fimp-eng", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void cleanup() {
        physicalDelete("erp_inv_freeze", freezeIds);
        physicalDelete("erp_inv_stock", stockIds);
        physicalDelete("erp_sd_reservation", resIds);
        physicalDelete("erp_proc_po_line", poLineIds);
        physicalDelete("erp_proc_po", poIds);
        for (String apprId : apprIds) {
            jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID = ?", apprId);
            physicalDelete("erp_sys_approval", List.of(apprId));
        }
        freezeIds.clear();
        stockIds.clear();
        resIds.clear();
        poIds.clear();
        poLineIds.clear();
        apprIds.clear();
        SecurityContextHolder.clearContext();
    }

    private void physicalDelete(String table, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        String ph = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update("DELETE FROM " + table + " WHERE ID IN (" + ph + ")", ids.toArray());
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private void insertStock(String avail) {
        InvStock s = new InvStock();
        s.setId("junit-fimp-" + System.nanoTime());
        stockIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("影响面测试物料");
        s.setBatchNo(BATCH);
        s.setAvailableQty(new BigDecimal(avail));
        s.setQcQty(BigDecimal.ZERO);
        s.setFinQty(BigDecimal.ZERO);
        s.setQty(new BigDecimal(avail));
        stockDao.insert(s);
    }

    private void insertReservation(String soNo, String qty) {
        Reservation r = new Reservation();
        r.setId("junit-fimpres-" + System.nanoTime());
        resIds.add(r.getId());
        r.setSoId("SO-FIMP");
        r.setSoNo(soNo);
        r.setLineId("SL-FIMP-1");
        r.setLineNo(1);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo(BATCH);
        r.setQty(new BigDecimal(qty));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(java.time.LocalDateTime.now());
        reservationDao.insert(r);
    }

    private void insertOpenPo(String poNo, String qty, String lineStatus, String headStatus) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("junit-fimppo-" + System.nanoTime());
        poIds.add(po.getId());
        po.setPoNo(poNo);
        po.setStatus(headStatus);
        po.setPoType("NORMAL");
        po.setSource("MANUAL");
        po.setSupplierId("SUP-FIMP");
        po.setSupplierName("影响面测试供应商");
        poDao.insert(po);

        PurchaseOrderLine l = new PurchaseOrderLine();
        l.setId("junit-fimppl-" + System.nanoTime());
        poLineIds.add(l.getId());
        l.setPoId(po.getId());
        l.setLineNo(1);
        l.setItemCode(ITEM);
        l.setQty(new BigDecimal(qty));
        l.setUnitPrice(new BigDecimal("10"));
        l.setAmount(new BigDecimal(qty).multiply(new BigDecimal("10")));
        l.setReceivedQty(BigDecimal.ZERO);
        l.setLineStatus(lineStatus);
        poLineDao.insert(l);
    }

    private InvFreeze applyAndApprove(String qty) {
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(WH);
        req.setItemCode(ITEM);
        req.setBatchNo(BATCH);
        req.setQty(new BigDecimal(qty));
        req.setReason("影响面快照测试");
        req.setScope(InvFreeze.SCOPE_BATCH);
        InvFreeze f = freezeService.apply(req);
        freezeIds.add(f.getId());
        apprIds.add(f.getApprId());
        ApprovalTask task = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, f.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        login("fimp-mgr", "ROLE_QUALITY_MGR");
        engine.pass(task.getId(), "同意");
        return freezeDao.selectById(f.getId());
    }

    // ================= 需求 ② 影响面快照 =================

    @Test
    void impactSnapshotWithReservationAndOpenPo() throws Exception {
        insertStock("100");
        insertReservation("SO-IMP-001", "30");
        insertOpenPo("PO-IMP-001", "50", "OPEN", "APPROVED");
        insertOpenPo("PO-IMP-CLOSED", "99", "OPEN", "CLOSED");      // 头已关闭不计
        insertOpenPo("PO-IMP-FULL", "40", "COMPLETED", "APPROVED"); // 行已收满不计

        InvFreeze done = applyAndApprove("30");
        assertEquals(InvFreeze.ST_ACTIVE, done.getStatus());
        assertNotNull(done.getImpactJson(), "执行须落影响面快照");

        JsonNode snap = jsonMapper.readTree(done.getImpactJson());
        assertEquals(1, snap.get("sos").size(), "SO 预留反查 1 条");
        assertEquals("SO-IMP-001", snap.get("sos").get(0).get("soNo").asText());
        assertEquals("BATCH", snap.get("sos").get(0).get("precision").asText());
        assertEquals(1, snap.get("pos").size(), "PO 仅 APPROVED+OPEN 计入");
        assertEquals("PO-IMP-001", snap.get("pos").get(0).get("poNo").asText());
        assertEquals("ITEM", snap.get("pos").get(0).get("precision").asText());
        assertTrue(snap.get("mos").isEmpty(), "工单数据源未落地恒空");
        assertFalse(snap.get("moDataSource").asBoolean(), "moDataSource=false（偏差 D1）");
    }

    @Test
    void impactSnapshotEmptyWhenNoReservation() throws Exception {
        insertStock("60");
        InvFreeze done = applyAndApprove("10");
        assertEquals(InvFreeze.ST_ACTIVE, done.getStatus());

        JsonNode snap = jsonMapper.readTree(done.getImpactJson());
        assertTrue(snap.get("sos").isEmpty(), "无预留 SO 清单为空数组");
        assertTrue(snap.get("pos").isEmpty(), "无未清 PO 为空数组");
        assertFalse(snap.get("moDataSource").asBoolean());
    }

    @Test
    void queryReturnsImpactJson() {
        insertStock("80");
        InvFreeze done = applyAndApprove("20");
        List<Map<String, Object>> rows = freezeService.query(InvFreeze.T_QUALITY, null,
                ITEM, BATCH, null);
        Map<String, Object> hit = rows.stream()
                .filter(r -> done.getId().equals(r.get("id")))
                .findFirst().orElseThrow();
        assertNotNull(hit.get("impactJson"), "query 须返回 impactJson 供 4.9.4 展开");
    }

    // ================= 需求 ③ 影响评估预估 =================

    @Test
    void estimateScopeAllAlwaysNeedsConfirm() {
        insertStock("100");
        Map<String, Object> m = freezeService.estimate(WH, ITEM, BATCH, InvFreeze.SCOPE_ALL,
                new BigDecimal("5"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) m.get("availableQty")));
        assertTrue((Boolean) m.get("needConfirm"), "scope=ALL 必须二次确认");
        assertEquals(50, (int) m.get("ratioPct"));
    }

    @Test
    void estimateLowRatioSkipsConfirm() {
        insertStock("100");
        Map<String, Object> m = freezeService.estimate(WH, ITEM, BATCH, InvFreeze.SCOPE_BATCH,
                new BigDecimal("10"));
        assertFalse((Boolean) m.get("needConfirm"), "10/100=10% < 50% 不触发");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) m.get("affectedQty")));
    }

    @Test
    void estimateHighRatioNeedsConfirm() {
        insertStock("100");
        Map<String, Object> m = freezeService.estimate(WH, ITEM, BATCH, InvFreeze.SCOPE_BATCH,
                new BigDecimal("50"));
        assertTrue((Boolean) m.get("needConfirm"), "50/100=50% ≥ 50% 触发");
    }

    @Test
    void estimateMissingDimension422() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> freezeService.estimate(WH, "IT-NO-EXIST", "B-NO", "BATCH",
                        BigDecimal.ONE));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("维度不存在"));
    }

    @Test
    void estimateCountsAffectedSo() {
        insertStock("100");
        insertReservation("SO-IMP-A", "20");
        insertReservation("SO-IMP-B", "10");
        insertReservation("SO-IMP-A", "5");   // 同 SO 两行去重
        Map<String, Object> m = freezeService.estimate(WH, ITEM, BATCH, InvFreeze.SCOPE_BATCH,
                new BigDecimal("10"));
        assertEquals(2L, m.get("affectedSoCount"), "按 SO 单号去重");
    }
}

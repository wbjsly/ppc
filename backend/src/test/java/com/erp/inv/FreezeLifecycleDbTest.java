package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCheckReportDao;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.dao.system.SysNoticeDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.entity.system.SysNotice;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.FreezeService;
import com.erp.service.inv.StockSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冻结/解冻生命周期 DB 集成（真实审批底座 + 真实 MySQL，spec freeze-management，
 * 任务 4.3/4.4/4.5/4.6）：申请→跨类型拒签→签署执行平移+预留释放+通知→
 * 解冻独立审批回补；执行量不足 422 回滚；NCR 台账隔离；查询与 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FreezeLifecycleDbTest {

    private static final String WH = "WH-FZ";
    private static final String ITEM = "IT-FZ-TEST";
    private static final String BATCH = "B-FZ-1";

    @Autowired
    private FreezeService freezeService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private StockSnapshotService snapshotService;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private InvFreezeDao freezeDao;
    @Autowired
    private ReservationDao reservationDao;
    @Autowired
    private ApprovalInstanceDao instanceDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private SysNoticeDao noticeDao;
    @Autowired
    private InvCheckReportDao checkReportDao;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> freezeIds = new ArrayList<>();
    private final List<String> stockIds = new ArrayList<>();
    private final List<String> resIds = new ArrayList<>();
    private final List<String> noticeIds = new ArrayList<>();
    private final List<String> apprIds = new ArrayList<>();

    @BeforeEach
    void purgeResidueAndLogin() {
        // 防御自清：历史轮次软删残留占用唯一键（stock UK / FREEZE_NO UK），物理清除
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID "
                + "WHERE a.BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('FREEZE','UNFREEZE')");
        login("fz-eng", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void cleanup() {
        // 物理删除：@TableLogic 软删行仍占用唯一键（库存 UK / FREEZE_NO UK），测试须真删
        physicalDelete("erp_inv_freeze", freezeIds);
        physicalDelete("erp_inv_stock", stockIds);
        physicalDelete("erp_sd_reservation", resIds);
        physicalDelete("erp_sys_notice", noticeIds);
        for (String apprId : apprIds) {
            jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID = ?", apprId);
            physicalDelete("erp_sys_approval", List.of(apprId));
        }
        freezeIds.clear();
        stockIds.clear();
        resIds.clear();
        noticeIds.clear();
        apprIds.clear();
        SecurityContextHolder.clearContext();
    }

    private void physicalDelete(String table, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update("DELETE FROM " + table + " WHERE ID IN (" + placeholders + ")",
                ids.toArray());
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private InvStock insertStock(String avail, String qc) {
        InvStock s = new InvStock();
        s.setId("junit-fz-" + System.nanoTime());
        stockIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("冻结测试物料");
        s.setBatchNo(BATCH);
        s.setAvailableQty(new BigDecimal(avail));
        s.setQcQty(new BigDecimal(qc));
        s.setFinQty(BigDecimal.ZERO);
        s.setQty(new BigDecimal(avail).add(new BigDecimal(qc)));
        stockDao.insert(s);
        return s;
    }

    private Reservation insertReservation(String qty) {
        Reservation r = new Reservation();
        r.setId("junit-fzres-" + System.nanoTime());
        resIds.add(r.getId());
        r.setSoId("SO-FZ");
        r.setSoNo("SO-FZ-001");
        r.setLineId("SL-FZ-1");
        r.setLineNo(1);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo(BATCH);
        r.setQty(new BigDecimal(qty));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(java.time.LocalDateTime.now());
        reservationDao.insert(r);
        return r;
    }

    private InvFreeze applyQuality(String qty) {
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(WH);
        req.setItemCode(ITEM);
        req.setBatchNo(BATCH);
        req.setQty(new BigDecimal(qty));
        req.setReason("冒烟不合格隔离");
        req.setScope(InvFreeze.SCOPE_BATCH);
        InvFreeze f = freezeService.apply(req);
        freezeIds.add(f.getId());
        if (f.getApprId() != null) apprIds.add(f.getApprId());
        return f;
    }

    private ApprovalTask activeTask(String apprId) {
        return taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "ACTIVE")
                .orderByAsc(ApprovalTask::getSeq));
    }

    private void collectNotices(String bizId) {
        for (SysNotice n : noticeDao.selectList(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getBizId, bizId))) {
            noticeIds.add(n.getId());
        }
    }

    // ================= 主生命周期 =================

    @Test
    void qualityFreezeUnfreezeLifecycle() {
        insertStock("80", "20");       // 在手 100 = 可用 80 + 质检 20
        insertReservation("30");

        // 1) 申请（质量链）：PENDING + 节点角色 QUALITY_MGR
        InvFreeze f = applyQuality("30");
        assertEquals(InvFreeze.ST_PENDING, f.getStatus());
        assertNotNull(f.getApprId());
        ApprovalTask task = activeTask(f.getApprId());
        assertNotNull(task, "须生成可签节点");
        assertEquals("ROLE_QUALITY_MGR", task.getRoleRequired());

        // 2) 跨类型签署（C-4.4-05）：财务主管签质量冻结 → 403
        login("fz-finmgr", "ROLE_FINANCE_MGR");
        ServiceException cross = assertThrows(ServiceException.class,
                () -> engine.pass(task.getId(), "越权尝试"));
        assertEquals(403, cross.getCode());

        // 3) 质量经理签署 → 回调同事务执行
        login("fz-mgr", "ROLE_QUALITY_MGR");
        engine.pass(task.getId(), "同意冻结");

        InvStock s = stockDao.selectById(stockIds.get(0));
        assertEquals(0, new BigDecimal("50").compareTo(s.getAvailableQty()), "可用 80-30=50");
        assertEquals(0, new BigDecimal("50").compareTo(s.getQcQty()), "质检 20+30=50");
        assertEquals(0, new BigDecimal("100").compareTo(s.getQty()), "在手不变");
        InvFreeze done = freezeDao.selectById(f.getId());
        assertEquals(InvFreeze.ST_ACTIVE, done.getStatus());
        // 预留被释放（FR-4.4-5-3）
        Reservation released = reservationDao.selectById(resIds.get(0));
        assertEquals(Reservation.ST_RELEASED, released.getStatus());
        assertTrue(released.getReleaseReason().contains("冻结释放"), released.getReleaseReason());
        // 通知落表（FR-4.4-5-4，偏差 D1 仅落表）
        collectNotices(f.getFreezeNo());
        assertTrue(noticeIds.size() >= 1, "冻结通知须落表");
        // 4.3 快照口径：冻结列 = 50（含原 20 让步锁 + 新 30 质量冻结）
        Map<String, Object> snap = snapshotService.query(WH, ITEM, BATCH, null);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) snap.get("rows");
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) rows.get(0).get("frozen")));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) rows.get(0).get("available")));

        // 4) 解冻：非原发起人 403；依据必填 422；原发起人提交
        login("fz-other", "ROLE_QUALITY_ENG");
        assertThrows(ServiceException.class,
                () -> freezeService.applyUnfreeze(f.getId(), "已处理", "复检合格"));
        login("fz-eng", "ROLE_QUALITY_ENG");
        assertThrows(ServiceException.class,
                () -> freezeService.applyUnfreeze(f.getId(), " ", "复检合格"));

        InvFreeze u = freezeService.applyUnfreeze(f.getId(), "不合格已退货补货", "复检合格报告 RPT-1");
        freezeIds.add(u.getId());   // 同行，双删无害
        assertNotNull(u.getUnfreezeApprId());
        apprIds.add(u.getUnfreezeApprId());
        ApprovalTask uTask = activeTask(u.getUnfreezeApprId());
        assertEquals("ROLE_QUALITY_MGR", uTask.getRoleRequired(), "解冻独立审批同类型角色");

        // 跨类型解冻签署 → 403
        login("fz-finmgr", "ROLE_FINANCE_MGR");
        assertThrows(ServiceException.class, () -> engine.pass(uTask.getId(), "越权"));

        // 5) 质量经理签解冻 → 回补
        login("fz-mgr", "ROLE_QUALITY_MGR");
        engine.pass(uTask.getId(), "同意解冻");

        InvStock s2 = stockDao.selectById(stockIds.get(0));
        assertEquals(0, new BigDecimal("80").compareTo(s2.getAvailableQty()), "可用回补 50+30=80");
        assertEquals(0, new BigDecimal("20").compareTo(s2.getQcQty()), "质检回 20");
        InvFreeze rel = freezeDao.selectById(f.getId());
        assertEquals(InvFreeze.ST_RELEASED, rel.getStatus());
        assertNotNull(rel.getReleasedAt());
        collectNotices(f.getFreezeNo());
    }

    // ================= 执行量不足 → 422 回滚（签署与业务同事务） =================

    @Test
    void executionInsufficientRollsBackApproval() {
        insertStock("20", "0");       // 可用仅 20
        InvFreeze f = applyQuality("50");
        ApprovalTask task = activeTask(f.getApprId());

        login("fz-mgr", "ROLE_QUALITY_MGR");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.pass(task.getId(), "同意"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("可用量不足"), ex.getMessage());

        // 同事务回滚：库存不变、冻结单仍 PENDING、节点仍可签、无通知
        InvStock s = stockDao.selectById(stockIds.get(0));
        assertEquals(0, new BigDecimal("20").compareTo(s.getAvailableQty()));
        assertEquals(0, new BigDecimal("0").compareTo(s.getQcQty()));
        assertEquals(InvFreeze.ST_PENDING, freezeDao.selectById(f.getId()).getStatus());
        assertEquals("ACTIVE", activeTask(f.getApprId()).getStatus(), "签署回滚仍可重签");
        collectNotices(f.getFreezeNo());
        assertTrue(noticeIds.isEmpty(), "执行失败不得留通知");
    }

    // ================= NCR 台账隔离（任务 4.5） =================

    @Test
    void ncrLedgerIsolatedFromManual() {
        // 手工 ACTIVE 行（他单）
        InvFreeze manual = new InvFreeze();
        manual.setId("junit-fz-m-" + System.nanoTime());
        freezeIds.add(manual.getId());
        manual.setFreezeNo("FZMAN-" + System.nanoTime() % 100000);
        manual.setFreezeType(InvFreeze.T_QUALITY);
        manual.setWarehouseCode(WH);
        manual.setItemCode(ITEM);
        manual.setBatchNo(BATCH);
        manual.setQty(new BigDecimal("5"));
        manual.setReason("手工冻结");
        manual.setScope(InvFreeze.SCOPE_BATCH);
        manual.setStatus(InvFreeze.ST_ACTIVE);
        manual.setSource(InvFreeze.SRC_MANUAL);
        manual.setApplyBy("someone");
        freezeDao.insert(manual);

        // NCR 冻结入台账（SOURCE=NCR 即时生效）
        freezeService.recordNcrFreeze(WH, ITEM, BATCH, new BigDecimal("10"), "NCR-JT-1");
        InvFreeze ncrRow = freezeDao.selectOne(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getNcrId, "NCR-JT-1"));
        assertNotNull(ncrRow, "NCR 冻结须入台账");
        assertEquals(InvFreeze.SRC_NCR, ncrRow.getSource());
        assertEquals(InvFreeze.ST_ACTIVE, ncrRow.getStatus());
        freezeIds.add(ncrRow.getId());

        // NCR 解冻：仅释放 NCR 自有行，手工行保持 ACTIVE
        freezeService.recordNcrUnfreeze(WH, ITEM, BATCH, new BigDecimal("10"), "NCR-JT-1");
        assertEquals(InvFreeze.ST_RELEASED,
                freezeDao.selectById(ncrRow.getId()).getStatus());
        assertEquals(InvFreeze.ST_ACTIVE, freezeDao.selectById(manual.getId()).getStatus(),
                "解冻不得误放手工冻结行");

        // NCR 来源在查询中标只读、无解冻入口
        login("fz-eng", "ROLE_QUALITY_ENG", "ROLE_WAREHOUSE");
        List<Map<String, Object>> rows = freezeService.query(null, InvFreeze.ST_ACTIVE,
                ITEM, BATCH, null);
        for (Map<String, Object> row : rows) {
            if ("NCR-JT-1".equals(row.get("ncrId"))) {
                assertEquals(Boolean.TRUE, row.get("readonly"));
                assertEquals(Boolean.FALSE, row.get("canUnfreeze"));
            }
        }
    }

    /** 占位：recordNcrFreeze 的行由上方查询收集，无需额外句柄 */

    // ================= 查询与 401（任务 4.6） =================

    @Test
    void queryFiltersAndUnauthenticated401() throws Exception {
        insertStock("80", "20");
        InvFreeze f = applyQuality("10");
        login("fz-eng", "ROLE_QUALITY_ENG", "ROLE_WAREHOUSE");
        List<Map<String, Object>> rows = freezeService.query(InvFreeze.T_QUALITY,
                InvFreeze.ST_PENDING, ITEM, BATCH, null);
        assertEquals(1, rows.size());
        assertEquals(f.getFreezeNo(), rows.get(0).get("freezeNo"));
        assertEquals(Boolean.FALSE, rows.get(0).get("canUnfreeze"), "PENDING 行不可解冻");

        // 类型筛掉
        assertTrue(freezeService.query(InvFreeze.T_FINANCE, null, ITEM, null, null).isEmpty());

        // 未登录 401
        mockMvc.perform(get("/api/inv/freeze")).andExpect(status().isUnauthorized());
    }
}

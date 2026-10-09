package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.InvWaveDao;
import com.erp.dao.inv.InvWaveLineDao;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.InvWaveLine;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.FreezePauseService;
import com.erp.service.inv.FreezeService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结挂起/恢复 DB 集成（spec freeze-management ADDED 需求 ①，design D1，
 * 任务 3.2/3.3）：冻结执行同事务挂拣货任务/波次（状态白名单）、STAGING 不挂、
 * 解冻不自动恢复、恢复须来源冻结 RELEASED、CAS 回原状态、非法恢复 422。
 */
@SpringBootTest
class FreezePauseDbTest {

    private static final String WH = "WH-FPAUSE";
    private static final String ITEM = "IT-FPAUSE-TEST";
    private static final String BATCH = "B-FPAUSE-1";

    @Autowired
    private FreezeService freezeService;
    @Autowired
    private FreezePauseService pauseService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private InvFreezeDao freezeDao;
    @Autowired
    private PickTaskDao taskDao;
    @Autowired
    private PickTaskLineDao taskLineDao;
    @Autowired
    private InvWaveDao waveDao;
    @Autowired
    private InvWaveLineDao waveLineDao;
    @Autowired
    private ApprovalTaskDao taskApprDao;
    @Autowired
    private com.erp.service.inv.PickTaskService pickTaskService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> freezeIds = new ArrayList<>();
    private final List<String> stockIds = new ArrayList<>();
    private final List<String> taskIds = new ArrayList<>();
    private final List<String> taskLineIds = new ArrayList<>();
    private final List<String> waveIds = new ArrayList<>();
    private final List<String> waveLineIds = new ArrayList<>();
    private final List<String> apprIds = new ArrayList<>();

    @BeforeEach
    void purgeAndLogin() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID "
                + "WHERE a.BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('Freeze','Unfreeze')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE pl FROM erp_pick_task_line pl "
                + "JOIN erp_pick_task t ON pl.TASK_ID = t.ID WHERE t.SRC_DOC_NO LIKE 'FP-%'");
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO LIKE 'FP-%'");
        jdbc.update("DELETE wl FROM erp_inv_wave_line wl "
                + "JOIN erp_inv_wave w ON wl.WAVE_ID = w.ID WHERE w.WAVE_NO LIKE 'WFP-%'");
        jdbc.update("DELETE FROM erp_inv_wave WHERE WAVE_NO LIKE 'WFP-%'");
        login("fp-eng", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void cleanup() {
        physicalDelete("erp_inv_freeze", freezeIds);
        physicalDelete("erp_inv_stock", stockIds);
        physicalDelete("erp_pick_task_line", taskLineIds);
        physicalDelete("erp_pick_task", taskIds);
        physicalDelete("erp_inv_wave_line", waveLineIds);
        physicalDelete("erp_inv_wave", waveIds);
        for (String apprId : apprIds) {
            jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID = ?", apprId);
            physicalDelete("erp_sys_approval", List.of(apprId));
        }
        freezeIds.clear();
        stockIds.clear();
        taskIds.clear();
        taskLineIds.clear();
        waveIds.clear();
        waveLineIds.clear();
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

    private void insertStock() {
        InvStock s = new InvStock();
        s.setId("junit-fp-" + System.nanoTime());
        stockIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("挂起测试物料");
        s.setBatchNo(BATCH);
        s.setAvailableQty(new BigDecimal("100"));
        s.setQcQty(BigDecimal.ZERO);
        s.setFinQty(BigDecimal.ZERO);
        s.setQty(new BigDecimal("100"));
        stockDao.insert(s);
    }

    private String insertTask(String status) {
        PickTask t = new PickTask();
        t.setId("junit-fptask-" + System.nanoTime());
        taskIds.add(t.getId());
        t.setTaskNo("PT-FP-" + taskIds.size());
        t.setSrcType("SALES_OUT");
        t.setSrcDocNo("FP-SO-" + taskIds.size());
        t.setStatus(status);
        taskDao.insert(t);

        PickTaskLine l = new PickTaskLine();
        l.setId("junit-fptl-" + System.nanoTime());
        taskLineIds.add(l.getId());
        l.setTaskId(t.getId());
        l.setLineNo(1);
        l.setItemCode(ITEM);
        l.setWarehouseCode(WH);
        l.setBatchNo(BATCH);
        l.setQty(new BigDecimal("10"));
        l.setLineStatus("PENDING");
        l.setScanOkFlag("0");
        taskLineDao.insert(l);
        return t.getId();
    }

    private String insertWave(String status) {
        InvWave w = new InvWave();
        w.setId("junit-fpw-" + System.nanoTime());
        waveIds.add(w.getId());
        w.setWaveNo("WFP-" + waveIds.size());
        w.setStatus(status);
        w.setDocCount(1);
        waveDao.insert(w);

        InvWaveLine l = new InvWaveLine();
        l.setId("junit-fpwl-" + System.nanoTime());
        waveLineIds.add(l.getId());
        l.setWaveId(w.getId());
        l.setShipId("SH-FP");
        l.setLineNo(1);
        l.setItemCode(ITEM);
        l.setWarehouseCode(WH);
        l.setBatchNo(BATCH);
        l.setQty(new BigDecimal("10"));
        l.setLockFlag("0");
        waveLineDao.insert(l);
        return w.getId();
    }

    /** 申请 + 质量经理签署 → 执行（挂起在回调同事务内发生） */
    private InvFreeze applyAndApprove(String qty) {
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(WH);
        req.setItemCode(ITEM);
        req.setBatchNo(BATCH);
        req.setQty(new BigDecimal(qty));
        req.setReason("挂起链路测试");
        req.setScope(InvFreeze.SCOPE_BATCH);
        InvFreeze f = freezeService.apply(req);
        freezeIds.add(f.getId());
        apprIds.add(f.getApprId());
        ApprovalTask at = taskApprDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, f.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        login("fp-mgr", "ROLE_QUALITY_MGR");
        engine.pass(at.getId(), "同意");
        return freezeDao.selectById(f.getId());
    }

    private void applyUnfreeze(InvFreeze f) {
        login("fp-eng", "ROLE_QUALITY_ENG");
        InvFreeze req = freezeService.applyUnfreeze(f.getId(), "复检合格", "复检报告 RPT-FP-1");
        apprIds.add(req.getUnfreezeApprId());
        ApprovalTask at = taskApprDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, req.getUnfreezeApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        login("fp-mgr", "ROLE_QUALITY_MGR");
        engine.pass(at.getId(), "同意解冻");
    }

    // ================= 需求 ① 挂起 =================

    @Test
    void freezePausesPickTaskAndWave() {
        insertStock();
        String taskId = insertTask(PickTask.ST_PICKING);
        String waveId = insertWave(InvWave.ST_SORTING);

        InvFreeze done = applyAndApprove("30");
        assertEquals(InvFreeze.ST_ACTIVE, done.getStatus());

        PickTask t = taskDao.selectById(taskId);
        assertEquals(PickTask.ST_PAUSED, t.getStatus(), "PICKING 任务须挂起");
        assertEquals(PickTask.ST_PICKING, t.getPauseFromStatus(), "记录暂停前状态");
        assertEquals(done.getFreezeNo(), t.getPauseFreezeNo(), "记录来源冻结单");

        InvWave w = waveDao.selectById(waveId);
        assertEquals(InvWave.ST_PAUSED, w.getStatus(), "SORTING 波次须挂起");
        assertEquals(InvWave.ST_SORTING, w.getPauseFromStatus());
        assertEquals(done.getFreezeNo(), w.getPauseFreezeNo());
    }

    @Test
    void stagingWaveNotPaused() {
        insertStock();
        String waveId = insertWave(InvWave.ST_STAGING);
        String pickDoneId = insertTask(PickTask.ST_PICKED);

        InvFreeze done = applyAndApprove("20");

        InvWave w = waveDao.selectById(waveId);
        assertEquals(InvWave.ST_STAGING, w.getStatus(), "STAGING 不挂（过账引擎兜底）");
        assertNull(w.getPauseFreezeNo());
        PickTask t = taskDao.selectById(pickDoneId);
        assertEquals(PickTask.ST_PICKED, t.getStatus(), "PICKED 不挂");
    }

    @Test
    void unfreezeDoesNotAutoResume() {
        insertStock();
        String taskId = insertTask(PickTask.ST_CREATED);
        String waveId = insertWave(InvWave.ST_ALLOCATED);

        InvFreeze done = applyAndApprove("10");
        applyUnfreeze(done);
        InvFreeze released = freezeDao.selectById(done.getId());
        assertEquals(InvFreeze.ST_RELEASED, released.getStatus());

        PickTask t = taskDao.selectById(taskId);
        assertEquals(PickTask.ST_PAUSED, t.getStatus(), "解冻不自动恢复（spec 需求①）");
        InvWave w = waveDao.selectById(waveId);
        assertEquals(InvWave.ST_PAUSED, w.getStatus());
    }

    // ================= 需求 ① 恢复 =================

    @Test
    void resumeBlockedWhileFreezeActive() {
        insertStock();
        String taskId = insertTask(PickTask.ST_CREATED);
        InvFreeze done = applyAndApprove("10");   // ACTIVE 未解冻
        login("fp-wh", "ROLE_WAREHOUSE");

        ServiceException e = assertThrows(ServiceException.class,
                () -> pauseService.resume("PICK_TASK", taskId));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("尚未解冻"));
        assertEquals(PickTask.ST_PAUSED, taskDao.selectById(taskId).getStatus());
    }

    @Test
    void resumeAfterReleaseRestoresOriginalStatus() {
        insertStock();
        String taskId = insertTask(PickTask.ST_PICKING);
        String waveId = insertWave(InvWave.ST_PICKING);

        InvFreeze done = applyAndApprove("10");
        applyUnfreeze(done);

        login("fp-wh", "ROLE_WAREHOUSE");
        pauseService.resume("PICK_TASK", taskId);
        PickTask t = taskDao.selectById(taskId);
        assertEquals(PickTask.ST_PICKING, t.getStatus(), "恢复回暂停前状态");
        assertNull(t.getPauseFreezeNo(), "清来源标记");
        assertNull(t.getPauseFromStatus());

        pauseService.resume("WAVE", waveId);
        InvWave w = waveDao.selectById(waveId);
        assertEquals(InvWave.ST_PICKING, w.getStatus());
        assertNull(w.getPauseFreezeNo());
    }

    @Test
    void resumeRequiresWarehouseRole() {
        insertStock();
        String taskId = insertTask(PickTask.ST_CREATED);
        InvFreeze done = applyAndApprove("10");
        applyUnfreeze(done);

        login("fp-eng", "ROLE_QUALITY_ENG");
        ServiceException e = assertThrows(ServiceException.class,
                () -> pauseService.resume("PICK_TASK", taskId));
        assertEquals(403, e.getCode());
    }

    @Test
    void resumeNonPaused422() {
        insertStock();
        String taskId = insertTask(PickTask.ST_PICKING);   // 未被挂起
        login("fp-wh", "ROLE_WAREHOUSE");
        ServiceException e = assertThrows(ServiceException.class,
                () -> pauseService.resume("PICK_TASK", taskId));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("非冻结挂起"));
    }

    @Test
    void resumeUnknownType422() {
        login("fp-wh", "ROLE_WAREHOUSE");
        ServiceException e = assertThrows(ServiceException.class,
                () -> pauseService.resume("NOPE", "x"));
        assertEquals(422, e.getCode());
    }

    @Test
    void pausedTaskCannotTransition() {
        insertStock();
        String taskId = insertTask(PickTask.ST_PICKING);
        applyAndApprove("10");
        assertEquals(PickTask.ST_PAUSED, taskDao.selectById(taskId).getStatus());

        // PAUSED 不在 PickTask TRANSITIONS 白名单 → 非法迁移 422（恢复只能走 resume）
        login("fp-wh", "ROLE_WAREHOUSE");
        var e = assertThrows(ServiceException.class,
                () -> pickTaskService.transition(taskId, PickTask.ST_PAUSED, PickTask.ST_PICKED));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }
}

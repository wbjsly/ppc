package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.impl.inv.FreezeCallback;
import com.erp.service.impl.inv.FreezeServiceImpl;
import com.erp.service.sd.ReservationService;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冻结规则单测（spec freeze-management，任务 4.1/4.2/4.4 校验与角色分流，
 * 4.3/4.4 执行回调逻辑）：C-4.4-05 单笔类型唯一与角色分流、必填 422、
 * 执行平移/回补 SQL 口径、可用量不足阻断、驳回状态机。
 */
class FreezeRulesUnitTest {

    private FreezeServiceImpl service;
    private FreezeCallback callback;
    private InvFreezeDao freezeDao;
    private InvStockDao stockDao;
    private ReservationDao reservationDao;
    private ApprovalEngine engine;
    private ReservationService reservationService;
    private NoticeService noticeService;

    @BeforeEach
    void setUp() {
        freezeDao = mock(InvFreezeDao.class);
        stockDao = mock(InvStockDao.class);
        reservationDao = mock(ReservationDao.class);
        engine = mock(ApprovalEngine.class);
        reservationService = mock(ReservationService.class);
        noticeService = mock(NoticeService.class);
        var poLineDao = mock(com.erp.dao.proc.PurchaseOrderLineDao.class);
        var poDao = mock(com.erp.dao.proc.PurchaseOrderDao.class);
        var sysParamService = mock(com.erp.service.SysParamService.class);
        when(sysParamService.getInt(eq("FREEZE_IMPACT_RATIO"), anyInt())).thenReturn(50);
        service = new FreezeServiceImpl(freezeDao, stockDao, engine, reservationService,
                reservationDao, sysParamService);
        var pauseService = mock(com.erp.service.inv.FreezePauseService.class);
        callback = new FreezeCallback(freezeDao, stockDao, reservationDao,
                reservationService, noticeService, poLineDao, poDao, pauseService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        // 影响面快照 PO 查询：单测无 PO 夹具，返回空表即 pos=[]
        when(poLineDao.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(poDao.selectBatchIds(any())).thenReturn(java.util.Collections.emptyList());
        when(freezeDao.selectCount(any())).thenReturn(0L);
        when(freezeDao.updateById(any())).thenReturn(1);
        org.mockito.Mockito.doAnswer(inv -> {
            ((InvFreeze) inv.getArgument(0)).setId("F1");
            return 1;
        }).when(freezeDao).insert(any(InvFreeze.class));
        when(stockDao.update(any(), any())).thenReturn(1);
        // 存在性校验改计数（位行多行下 selectOne 会撞 TooManyResults，spec freeze 适配）
        when(stockDao.selectCount(any())).thenReturn(1L);
        login("eng1", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private InvFreeze req(String type, String reason, String qty) {
        InvFreeze f = new InvFreeze();
        f.setFreezeType(type);
        f.setWarehouseCode("WH-MAIN");
        f.setItemCode("RM1");
        f.setBatchNo("B1");
        f.setQty(qty == null ? null : new BigDecimal(qty));
        f.setReason(reason);
        f.setScope(InvFreeze.SCOPE_BATCH);
        return f;
    }

    private ApprovalInstance inst(String bizType, String status) {
        ApprovalInstance i = mock(ApprovalInstance.class);
        when(i.getId()).thenReturn("appr-1");
        when(i.getBizType()).thenReturn(bizType);
        when(i.getBizId()).thenReturn("F1");
        when(i.getStatus()).thenReturn(status);
        return i;
    }

    /** 先建实例再入 when(...)，避免嵌套桩 UnfinishedStubbing */
    private void stubSubmitReturns(ApprovalInstance instance) {
        when(engine.submit(anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(instance);
    }

    private InvStock stock(String avail, String qc, String fin) {
        InvStock s = new InvStock();
        s.setId("S1");
        s.setWarehouseCode("WH-MAIN");
        s.setItemCode("RM1");
        s.setBatchNo("B1");
        s.setQty(new BigDecimal(avail).add(new BigDecimal(qc)).add(new BigDecimal(fin)));
        s.setAvailableQty(new BigDecimal(avail));
        s.setQcQty(new BigDecimal(qc));
        s.setFinQty(new BigDecimal(fin));
        return s;
    }

    private void stubStock(InvStock s) {
        when(stockDao.selectOne(any(LambdaQueryWrapper.class))).thenReturn(s);
        when(stockDao.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
    }

    private InvFreeze stored(String status, String source, String applyBy) {
        InvFreeze f = new InvFreeze();
        f.setId("F1");
        f.setFreezeNo("FZ261008-0001");
        f.setFreezeType(InvFreeze.T_QUALITY);
        f.setWarehouseCode("WH-MAIN");
        f.setItemCode("RM1");
        f.setBatchNo("B1");
        f.setQty(new BigDecimal("30"));
        f.setReason("不合格隔离");
        f.setScope(InvFreeze.SCOPE_BATCH);
        f.setStatus(status);
        f.setSource(source);
        f.setApplyBy(applyBy);
        return f;
    }

    // ================= 4.1 申请校验 =================

    @Test
    void applyRejectsInvalidInput() {
        stubStock(stock("80", "20", "0"));
        // 单笔类型唯一（C-4.4-05）
        assertThrows(ServiceException.class, () -> service.apply(req("MIXED", "r", "10")));
        // 原因必填
        assertThrows(ServiceException.class, () -> service.apply(req(InvFreeze.T_QUALITY, " ", "10")));
        // 数量必填且大于 0
        assertThrows(ServiceException.class, () -> service.apply(req(InvFreeze.T_QUALITY, "r", null)));
        assertThrows(ServiceException.class, () -> service.apply(req(InvFreeze.T_QUALITY, "r", "0")));
        // 物料/仓库必填
        InvFreeze noItem = req(InvFreeze.T_QUALITY, "r", "10");
        noItem.setItemCode(null);
        assertThrows(ServiceException.class, () -> service.apply(noItem));
        // 指定批次范围缺批次
        InvFreeze noBatch = req(InvFreeze.T_QUALITY, "r", "10");
        noBatch.setBatchNo(null);
        assertThrows(ServiceException.class, () -> service.apply(noBatch));
        verify(engine, never()).submit(anyString(), anyString(), anyString(), any(), any());
    }

    // ================= 4.2 角色分流与审批节点 =================

    @Test
    void qualityApplyBuildsQualityMgrNode() {
        stubStock(stock("80", "20", "0"));
        stubSubmitReturns(inst("Freeze", "PENDING"));
        service.apply(req(InvFreeze.T_QUALITY, "不合格", "10"));

        @SuppressWarnings("unchecked")
        var chains = ArgumentCaptor.forClass(List.class);
        verify(engine).submit(eq("Freeze"), anyString(), anyString(), isNull(), chains.capture());
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> seq = chains.getValue();
        assertEquals(1, seq.size());
        assertEquals("ROLE_QUALITY_MGR", seq.get(0).get(0).getRoleRequired());
    }

    @Test
    void financeApplyBuildsFinanceMgrNode() {
        login("fin1", "ROLE_FINANCE");
        stubStock(stock("80", "20", "0"));
        stubSubmitReturns(inst("Freeze", "PENDING"));
        service.apply(req(InvFreeze.T_FINANCE, "审计封存", "10"));

        @SuppressWarnings("unchecked")
        var chains = ArgumentCaptor.forClass(List.class);
        verify(engine).submit(eq("Freeze"), anyString(), anyString(), isNull(), chains.capture());
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> seq = chains.getValue();
        assertEquals("ROLE_FINANCE_MGR", seq.get(0).get(0).getRoleRequired());
    }

    @Test
    void initiatorRoleMatrixEnforced() {
        stubStock(stock("80", "20", "0"));
        // 财务角色发起质量冻结 → 403
        login("fin1", "ROLE_FINANCE");
        assertThrows(ServiceException.class,
                () -> service.apply(req(InvFreeze.T_QUALITY, "r", "10")));
        // 质量角色发起财务冻结 → 403
        login("eng1", "ROLE_QUALITY_ENG");
        assertThrows(ServiceException.class,
                () -> service.apply(req(InvFreeze.T_FINANCE, "r", "10")));
        // 普通用户 → 403
        login("user1", "ROLE_USER");
        assertThrows(ServiceException.class,
                () -> service.apply(req(InvFreeze.T_QUALITY, "r", "10")));
        verify(engine, never()).submit(anyString(), anyString(), anyString(), any(), any());
    }

    // ================= 4.4 解冻校验 =================

    @Test
    void unfreezeValidations() {
        // NCR 来源隔离
        when(freezeDao.selectById("F1")).thenReturn(stored(InvFreeze.ST_ACTIVE,
                InvFreeze.SRC_NCR, "eng1"));
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", "r", "b"));
        // 非生效态
        when(freezeDao.selectById("F1")).thenReturn(stored(InvFreeze.ST_PENDING,
                InvFreeze.SRC_MANUAL, "eng1"));
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", "r", "b"));
        // 依据必填
        when(freezeDao.selectById("F1")).thenReturn(stored(InvFreeze.ST_ACTIVE,
                InvFreeze.SRC_MANUAL, "eng1"));
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", " ", "b"));
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", "r", " "));
        // 非原发起人 → 403
        login("other", "ROLE_QUALITY_ENG");
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", "r", "b"));
        // 重复提交（底座已有 PENDING）→ 422
        login("eng1", "ROLE_QUALITY_ENG");
        ApprovalInstance pendingUnfreeze = inst("Unfreeze", "PENDING");
        when(engine.findByBiz(eq("Unfreeze"), anyString())).thenReturn(pendingUnfreeze);
        assertThrows(ServiceException.class, () -> service.applyUnfreeze("F1", "r", "b"));
        // 成功路径：原发起人 + 依据齐全 + 无 PENDING
        when(engine.findByBiz(eq("Unfreeze"), anyString())).thenReturn(null);
        stubSubmitReturns(inst("Unfreeze", "PENDING"));
        InvFreeze out = service.applyUnfreeze("F1", "问题已处理", "复检合格依据");
        verify(engine).submit(eq("Unfreeze"), eq("F1"), anyString(), isNull(), any());
        assertEquals(InvFreeze.ST_ACTIVE, out.getStatus());
    }

    // ================= 4.3/4.4 执行回调 =================

    @Test
    void executeFreezeShiftsColumnsAndReleasesReserved() {
        InvFreeze f = stored(InvFreeze.ST_PENDING, InvFreeze.SRC_MANUAL, "eng1");
        when(freezeDao.selectById("F1")).thenReturn(f);
        stubStock(stock("80", "20", "0"));
        when(reservationDao.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        callback.onApproved(inst("Freeze", "PENDING"));

        ArgumentCaptor<LambdaUpdateWrapper<InvStock>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(stockDao).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSet();
        assertTrue(sql.contains("AVAILABLE_QTY = AVAILABLE_QTY - 30"), sql);
        assertTrue(sql.contains("QC_QTY = QC_QTY + 30"), sql);
        verify(reservationService).releaseByBatch(eq("WH-MAIN"), eq("RM1"), eq("B1"),
                contains("冻结释放"));
        assertEquals(InvFreeze.ST_ACTIVE, f.getStatus());
        verify(noticeService).push(eq("ROLE_WAREHOUSE"), isNull(), contains("冻结生效"),
                anyString(), eq("FREEZE"), eq("FZ261008-0001"));
    }

    @Test
    void executeFreezeBlockedWhenInsufficient() {
        InvFreeze f = stored(InvFreeze.ST_PENDING, InvFreeze.SRC_MANUAL, "eng1");
        f.setQty(new BigDecimal("50"));
        when(freezeDao.selectById("F1")).thenReturn(f);
        stubStock(stock("20", "0", "0"));   // 可用仅 20 < 50

        ServiceException ex = assertThrows(ServiceException.class,
                () -> callback.onApproved(inst("Freeze", "PENDING")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("可用量不足"), ex.getMessage());
        assertEquals(InvFreeze.ST_PENDING, f.getStatus());
        verify(reservationService, never()).releaseByBatch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void executeUnfreezeReversesShift() {
        InvFreeze f = stored(InvFreeze.ST_ACTIVE, InvFreeze.SRC_MANUAL, "eng1");
        f.setReleaseResult("已处理");
        f.setReleaseBasis("复检合格");
        when(freezeDao.selectById("F1")).thenReturn(f);
        stubStock(stock("50", "50", "0"));   // 冻结 30 在 QC 中

        callback.onApproved(inst("Unfreeze", "PENDING"));

        ArgumentCaptor<LambdaUpdateWrapper<InvStock>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(stockDao).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSet();
        assertTrue(sql.contains("QC_QTY = QC_QTY - 30"), sql);
        assertTrue(sql.contains("AVAILABLE_QTY = AVAILABLE_QTY + 30"), sql);
        assertEquals(InvFreeze.ST_RELEASED, f.getStatus());
        verify(noticeService).push(eq("ROLE_WAREHOUSE"), isNull(), contains("解冻完成"),
                anyString(), eq("UNFREEZE"), eq("FZ261008-0001"));
    }

    @Test
    void unfreezeBlockedWhenHeldInsufficient() {
        InvFreeze f = stored(InvFreeze.ST_ACTIVE, InvFreeze.SRC_MANUAL, "eng1");
        f.setQty(new BigDecimal("40"));
        when(freezeDao.selectById("F1")).thenReturn(f);
        stubStock(stock("90", "10", "0"));   // QC 仅 10 < 40

        ServiceException ex = assertThrows(ServiceException.class,
                () -> callback.onApproved(inst("Unfreeze", "PENDING")));
        assertEquals(422, ex.getCode());
        assertEquals(InvFreeze.ST_ACTIVE, f.getStatus());
    }

    @Test
    void freezeRejectionMarksRejected() {
        InvFreeze f = stored(InvFreeze.ST_PENDING, InvFreeze.SRC_MANUAL, "eng1");
        when(freezeDao.selectById("F1")).thenReturn(f);
        callback.onRejected(inst("Freeze", "REJECTED"));
        assertEquals(InvFreeze.ST_REJECTED, f.getStatus());
    }

    @Test
    void unfreezeRejectionKeepsActive() {
        InvFreeze f = stored(InvFreeze.ST_ACTIVE, InvFreeze.SRC_MANUAL, "eng1");
        when(freezeDao.selectById("F1")).thenReturn(f);
        callback.onRejected(inst("Unfreeze", "REJECTED"));
        assertEquals(InvFreeze.ST_ACTIVE, f.getStatus());
        assertTrue(f.getRemark() != null && f.getRemark().contains("解冻驳回"), f.getRemark());
    }
}

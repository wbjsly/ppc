package com.erp.portal;

import com.erp.common.ServiceException;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.dao.proc.AsnDao;
import com.erp.dao.proc.AsnLineDao;
import com.erp.dao.proc.PoCoopLogDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.vmi.VmiAlertDao;
import com.erp.dao.proc.ReplenishConfirmDao;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.PoCoopLog;
import com.erp.entity.system.SysUser;
import com.erp.ops.OutboxPublisher;
import com.erp.security.PortalContext;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.impl.proc.AsnServiceImpl;
import com.erp.service.impl.proc.PoCoopServiceImpl;
import com.erp.service.impl.portal.PortalEventServiceImpl;
import com.erp.service.portal.PortalEventService;
import com.erp.entity.proc.PurchaseOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * 门户协同规则单测（spec add-supplier-portal-collaboration，tasks 9.2）：
 * 行级隔离上下文 / 水位同步幂等（小时桶+哈希）/ ASN 防超发 / 锁定同秒判定。
 */
class PortalRulesUnitTest {

    @BeforeEach
    void reset() {
        SecurityContextHolder.clearContext();
    }

    private void asPortal(String userId, String supplierId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER"))));
    }

    private void asInternal(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ---------- 门户上下文（行级隔离，C-4.9-06） ----------

    @Test
    void portalContextRejectsInternalUser() {
        asInternal("user-admin");
        PortalContext ctx = new PortalContext(mock(SysUserDao.class));
        ServiceException e = assertThrows(ServiceException.class, ctx::requireSupplierId);
        assertEquals(403, e.getCode(), "内部账号不得走门户上下文");
    }

    @Test
    void portalContextRejectsUnbound() {
        asPortal("user-x", null);
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectById("user-x")).thenReturn(user(null, "1"));
        PortalContext ctx = new PortalContext(dao);
        ServiceException e = assertThrows(ServiceException.class, ctx::requireSupplierId);
        assertEquals(403, e.getCode(), "未绑定供应商的门户账号默认拒绝");
    }

    @Test
    void portalContextReturnsBoundSupplier() {
        asPortal("user-p", "sup-001");
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectById("user-p")).thenReturn(user("sup-001", "1"));
        PortalContext ctx = new PortalContext(dao);
        assertEquals("sup-001", ctx.requireSupplierId());
    }

    @Test
    void portalContextRejectsDisabledAccount() {
        asPortal("user-d", "sup-001");
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectById("user-d")).thenReturn(user("sup-001", "0"));
        PortalContext ctx = new PortalContext(dao);
        ServiceException e = assertThrows(ServiceException.class, ctx::requireSupplierId);
        assertEquals(403, e.getCode(), "停用账号立即失访（spec 门户账号生命周期）");
    }

    // ---------- 水位同步幂等（design D10：小时桶 + 快照哈希） ----------

    private PortalEventServiceImpl waterService(MdmOutboxDao outboxDao) {
        return new PortalEventServiceImpl(mock(OutboxPublisher.class), outboxDao);
    }

    @Test
    void waterSyncSkipsSameHourBucket() {
        MdmOutboxDao dao = mock(MdmOutboxDao.class);
        when(dao.selectCount(any())).thenReturn(1L);   // 同小时桶已存在
        PortalEventService svc = waterService(dao);
        boolean written = svc.waterSynced("sup-001", "hash-abc", List.of(new HashMap<>()));
        assertFalse(written, "同桶重复必须跳过（不重复生成批次）");
        verify(mock(OutboxPublisher.class), never()).publishSourced(anyString(), anyString(),
                anyInt(), any(), anyString(), any(), anyString());
    }

    @Test
    void waterSyncSkipsUnchangedSnapshot() {
        MdmOutboxDao dao = mock(MdmOutboxDao.class);
        when(dao.selectCount(any())).thenReturn(0L);
        com.erp.entity.ops.MdmOutboxEvent last = new com.erp.entity.ops.MdmOutboxEvent();
        last.setPayload("{\"diff\":\"hash-abc\",\"supplierId\":\"sup-001\"}");
        when(dao.selectOne(any())).thenReturn(last);   // 最近批次哈希相同
        PortalEventService svc = waterService(dao);
        assertFalse(svc.waterSynced("sup-001", "hash-abc", List.of(new HashMap<>())),
                "无变化快照不写新批次");
    }

    @Test
    void waterSyncWritesFreshSnapshot() {
        MdmOutboxDao dao = mock(MdmOutboxDao.class);
        when(dao.selectCount(any())).thenReturn(0L);
        when(dao.selectOne(any())).thenReturn(null);
        OutboxPublisher pub = mock(OutboxPublisher.class);
        PortalEventService svc = new PortalEventServiceImpl(pub, dao);
        assertTrue(svc.waterSynced("sup-001", "hash-new", List.of(new HashMap<>())),
                "全新快照应写入批次");
        verify(pub, times(1)).publishSourced(contains("VMI.WATER_SYNCED"), contains("WATER:sup-001"),
                eq(1), any(), contains("hash-new"), any(), anyString());
    }

    // ---------- ASN 防超发（design D7） ----------

    @Test
    void asnCreateRejectsWhenNoOpenQty() {
        AsnDao asnDao = mock(AsnDao.class);
        AsnLineDao lineDao = mock(AsnLineDao.class);
        PurchaseOrderDao poDao = mock(PurchaseOrderDao.class);
        PurchaseOrderLineDao poLineDao = mock(PurchaseOrderLineDao.class);

        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-1");
        po.setStatus("APPROVED");
        po.setConfirmStatus("CONFIRMED");
        when(poDao.selectById("po-1")).thenReturn(po);

        com.erp.entity.proc.PurchaseOrderLine pl = new com.erp.entity.proc.PurchaseOrderLine();
        pl.setId("pl-1");
        pl.setPoId("po-1");
        pl.setItemCode("RM0001");
        pl.setQty(new BigDecimal("100"));
        pl.setReceivedQty(new BigDecimal("100"));     // 已收满 → 未清 0
        when(poLineDao.selectById("pl-1")).thenReturn(pl);
        when(poLineDao.selectList(any())).thenReturn(List.of(pl));
        when(lineDao.selectList(any())).thenReturn(List.of());   // 无在途 ASN

        AsnServiceImpl svc = new AsnServiceImpl(asnDao, lineDao, poDao, poLineDao,
                mock(ApprovalEngine.class), mock(PortalEventService.class),
                mock(ReplenishConfirmDao.class), mock(VmiAlertDao.class));
        ReflectionTestUtils.setField(svc, "tolerance", new BigDecimal("0.005"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("poId", "po-1");
        payload.put("lines", List.of(Map.of("poLineId", "pl-1", "qty", 5)));
        ServiceException e = assertThrows(ServiceException.class, () -> svc.create(payload));
        assertTrue(e.getMessage().contains("剩余可发数量"), e.getMessage());
    }

    // ---------- 锁定同秒判定（DATETIME 秒精度，回归锁定/解锁） ----------

    @Test
    void lockUnlockedWithinSameSecond() {
        PoCoopLogDao coopDao = mock(PoCoopLogDao.class);
        PoCoopLog lock = new PoCoopLog();
        lock.setActionType(PoCoopLog.ACT_LOCK);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        lock.setActionAt(now);
        PoCoopLog unlock = new PoCoopLog();
        unlock.setActionType(PoCoopLog.ACT_UNLOCK);
        unlock.setActionAt(now);                      // 与 LOCK 同秒
        // supplierLocked 内部按序先查 LOCK 再查 UNLOCK（wrapper 不暴露参数值 → 按调用次序区分）
        final boolean[] onlyLock = {false};
        final java.util.concurrent.atomic.AtomicInteger callIdx =
                new java.util.concurrent.atomic.AtomicInteger(0);
        when(coopDao.selectOne(any())).thenAnswer(inv -> {
            boolean isLockQuery = (callIdx.getAndIncrement() % 2) == 0;
            if (isLockQuery) {
                return lock;
            }
            return onlyLock[0] ? null : unlock;
        });

        PoCoopServiceImpl svc = new PoCoopServiceImpl(coopDao, mock(PurchaseOrderDao.class),
                mock(PurchaseOrderLineDao.class), mock(PortalEventService.class));
        assertFalse(svc.supplierLocked("sup-x"), "同秒解锁后不得仍判锁定（unlock >= lock 视为已解锁）");

        onlyLock[0] = true;                            // 仅有 LOCK（无 UNLOCK）→ 锁定
        assertTrue(svc.supplierLocked("sup-x"), "仅有 LOCK 时处于锁定态");
    }

    private static SysUser user(String supplierId, String status) {
        SysUser u = new SysUser();
        u.setId("u");
        u.setStatus(status);
        u.setSupplierId(supplierId);
        return u;
    }
}

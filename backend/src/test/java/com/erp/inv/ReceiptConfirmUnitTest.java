package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.inv.InvPutawayDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.AsnDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.proc.PurchaseOrderVersionDao;
import com.erp.dao.proc.ReceiptAdjustmentDao;
import com.erp.dao.proc.ReceiptDifferenceDao;
import com.erp.dao.qms.ConcessionDao;
import com.erp.dao.vmi.VmiAgreementDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.ops.OutboxPublisher;
import com.erp.service.fin.AccrualService;
import com.erp.service.impl.proc.GoodsReceiptServiceImpl;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.proc.AsnService;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库确认状态机单测（spec inbound-workbench，任务 5.1，FR-4.4-1-7）：
 * POSTED→CONFIRMED 留痕、CREATED 拒 422、重复确认 422、非授权 403、并发 409、不可逆。
 */
class ReceiptConfirmUnitTest {

    private GoodsReceiptServiceImpl service;
    private GoodsReceiptDao grDao;

    @BeforeEach
    void setUp() {
        grDao = mock(GoodsReceiptDao.class);
        service = new GoodsReceiptServiceImpl(
                grDao, mock(GoodsReceiptLineDao.class), mock(ReceiptDifferenceDao.class),
                mock(ReceiptAdjustmentDao.class), mock(InvStockDao.class),
                mock(PurchaseOrderDao.class), mock(PurchaseOrderLineDao.class),
                mock(PurchaseOrderVersionDao.class), mock(MdmSupplierDao.class),
                mock(MdmItemDao.class), mock(OutboxPublisher.class),
                mock(InspectionLotService.class), mock(ConcessionDao.class),
                mock(AccrualService.class), mock(AsnService.class), mock(AsnDao.class),
                mock(VmiStockDao.class), mock(VmiAgreementDao.class),
                mock(VmiAgreementService.class), mock(VmiAlertService.class),
                mock(ObjectMapper.class), mock(StockPostingEngine.class),
                mock(InvPutawayDao.class));
        ReflectionTestUtils.setField(service, "receiptTolerance", new BigDecimal("0.005"));
        ReflectionTestUtils.setField(service, "qcHoursA", 24);
        ReflectionTestUtils.setField(service, "qcHoursB", 48);
        ReflectionTestUtils.setField(service, "qcHoursC", 72);
        login("wh1", "ROLE_WAREHOUSE");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private GoodsReceipt gr(String status) {
        GoodsReceipt g = new GoodsReceipt();
        g.setId("gr-1");
        g.setGrNo("GR-CONF-1");
        g.setStatus(status);
        return g;
    }

    @Test
    void postedConfirmSucceeds() {
        when(grDao.selectById("gr-1")).thenReturn(gr(GoodsReceipt.ST_POSTED));
        when(grDao.updateById(any(GoodsReceipt.class))).thenReturn(1);

        GoodsReceipt out = service.confirm("gr-1");

        assertEquals(GoodsReceipt.ST_CONFIRMED, out.getStatus());
        assertNotNull(out.getConfirmBy());
        assertNotNull(out.getConfirmAt());
    }

    @Test
    void createdCannotConfirm() {
        when(grDao.selectById("gr-1")).thenReturn(gr(GoodsReceipt.ST_CREATED));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm("gr-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仅已过账"), ex.getMessage());
        verify(grDao, never()).updateById(any(GoodsReceipt.class));
    }

    @Test
    void confirmedIrreversible() {
        when(grDao.selectById("gr-1")).thenReturn(gr(GoodsReceipt.ST_CONFIRMED));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm("gr-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可重复确认"), ex.getMessage());
    }

    @Test
    void nonAuthorizedRejected() {
        login("rcv1", "ROLE_RECEIVER");
        when(grDao.selectById("gr-1")).thenReturn(gr(GoodsReceipt.ST_POSTED));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm("gr-1"));
        assertEquals(403, ex.getCode());
        verify(grDao, never()).updateById(any(GoodsReceipt.class));
        // 未认证（无上下文）
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.confirm("gr-1"));
    }

    @Test
    void concurrentUpdateConflict() {
        when(grDao.selectById("gr-1")).thenReturn(gr(GoodsReceipt.ST_POSTED));
        when(grDao.updateById(any(GoodsReceipt.class))).thenReturn(0);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm("gr-1"));
        assertEquals(409, ex.getCode());
    }
}

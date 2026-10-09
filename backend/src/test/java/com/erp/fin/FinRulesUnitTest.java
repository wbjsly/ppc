package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccrualDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.fin.FinAccount;
import com.erp.entity.fin.FinVoucher;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.impl.fin.AccrualServiceImpl;
import com.erp.service.impl.fin.GlVoucherServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 最小总账凭证骨架 + VMI 暂估触发点的服务层直调单测
 * （spec gl-voucher：借贷不平 L1 / 停用科目 / POSTED 拒改；
 *   spec ap-accrual：VMI 来源仅 VMI_TRANSFER、GR 手工直调与非法来源 422、必填校验）。
 * 这些规则没有对外 HTTP 入口（本期无凭证录入页、VMI 无 UI 调用方），故以单测覆盖。
 */
class FinRulesUnitTest {

    private FinAccountDao accountDao;
    private FinVoucherDao voucherDao;
    private FinVoucherLineDao lineDao;
    private MdmLegalEntityDao legalEntityDao;
    private GlVoucherServiceImpl voucherService;

    private FinAccrualDao accrualDao;
    private GoodsReceiptDao grDao;
    private GoodsReceiptLineDao grLineDao;
    private GlVoucherService voucherServiceMock;
    private AccrualServiceImpl accrualService;

    private FinAccount activeAccount(String code) {
        FinAccount a = new FinAccount();
        a.setAccountCode(code);
        a.setAccountName("测试科目");
        a.setDirection("DR");
        a.setStatus("ACTIVE");
        return a;
    }

    @BeforeEach
    void setUp() {
        accountDao = mock(FinAccountDao.class);
        voucherDao = mock(FinVoucherDao.class);
        lineDao = mock(FinVoucherLineDao.class);
        legalEntityDao = mock(MdmLegalEntityDao.class);
        voucherService = new GlVoucherServiceImpl(accountDao, voucherDao, lineDao, legalEntityDao);

        accrualDao = mock(FinAccrualDao.class);
        grDao = mock(GoodsReceiptDao.class);
        grLineDao = mock(GoodsReceiptLineDao.class);
        voucherServiceMock = mock(GlVoucherService.class);
        accrualService = new AccrualServiceImpl(accrualDao, voucherServiceMock,
                grDao, grLineDao, null);
    }

    private List<GlVoucherService.FinVoucherLineSpec> lines(String d1, String a1,
                                                            String d2, String a2) {
        return List.of(
                GlVoucherService.FinVoucherLineSpec.of("1403", d1, new BigDecimal(a1), "行1"),
                GlVoucherService.FinVoucherLineSpec.of("2203", d2, new BigDecimal(a2), "行2"));
    }

    // ---------------- gl-voucher ----------------

    @Test
    void unbalancedLinesRejectedWithoutPersisting() {
        when(accountDao.selectActiveByCode(anyString())).thenReturn(activeAccount("1403"));
        ServiceException e = assertThrows(ServiceException.class, () ->
                voucherService.create(FinVoucher.TYPE_ACCRUAL, LocalDate.now(), "不平衡",
                        "GR", "IV-TEST", null, lines("DR", "100.00", "CR", "99.99")));
        assertTrue(e.getMessage().contains("借贷不平"), e.getMessage());
        assertTrue(e.getMessage().contains("0.01"), e.getMessage());
        verify(voucherDao, never()).insert(any(FinVoucher.class));
        verify(lineDao, never()).insert(any());
    }

    @Test
    void disabledAccountRejected() {
        FinAccount disabled = activeAccount("1403");
        disabled.setStatus("DISABLED");
        when(accountDao.selectActiveByCode("1403")).thenReturn(disabled);
        ServiceException e = assertThrows(ServiceException.class, () ->
                voucherService.create(FinVoucher.TYPE_ACCRUAL, LocalDate.now(), "停用科目",
                        "GR", "IV-TEST", null, lines("DR", "100.00", "CR", "100.00")));
        assertTrue(e.getMessage().contains("停用"), e.getMessage());
        verify(voucherDao, never()).insert(any(FinVoucher.class));
    }

    @Test
    void missingAccountRejected() {
        when(accountDao.selectActiveByCode(anyString())).thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class, () ->
                voucherService.create(FinVoucher.TYPE_ACCRUAL, LocalDate.now(), "缺科目",
                        "GR", "IV-TEST", null, lines("DR", "100.00", "CR", "100.00")));
        assertTrue(e.getMessage().contains("科目不存在"), e.getMessage());
    }

    @Test
    void postedVoucherRejectsUpdateAndDelete() {
        FinVoucher v = new FinVoucher();
        v.setId("v-posted");
        v.setVoucherNo("ACC202610001");
        v.setStatus(FinVoucher.STATUS_POSTED);
        when(voucherDao.selectById("v-posted")).thenReturn(v);

        ServiceException u = assertThrows(ServiceException.class,
                () -> voucherService.update("v-posted", "改摘要"));
        assertTrue(u.getMessage().contains("红字冲销"), u.getMessage());

        ServiceException d = assertThrows(ServiceException.class,
                () -> voucherService.voidDraft("v-posted"));
        assertTrue(d.getMessage().contains("红字冲销"), d.getMessage());
    }

    @Test
    void draftVoucherCanBeVoided() {
        FinVoucher v = new FinVoucher();
        v.setId("v-draft");
        v.setVoucherNo("ACC202610002");
        v.setStatus(FinVoucher.STATUS_DRAFT);
        when(voucherDao.selectById("v-draft")).thenReturn(v);
        voucherService.voidDraft("v-draft");
        verify(voucherDao).deleteById("v-draft");
    }

    // ---------------- VMI 暂估触发点（ap-accrual d）----------------

    @Test
    void vmiRejectsIllegalSourceType() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer("MANUAL", "VC-FIN-001",
                        "sup-x", "供应商", "PO-X", new BigDecimal("10"), new BigDecimal("50")));
        assertTrue(e.getMessage().contains("仅支持 GR 与 VMI_TRANSFER"), e.getMessage());
        verify(accrualDao, never()).insert(any(FinAccrual.class));
    }

    @Test
    void vmiRejectsManualGrSourceTypeCall() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer("GR", "VC-FIN-001",
                        "sup-x", "供应商", "PO-X", new BigDecimal("10"), new BigDecimal("50")));
        assertTrue(e.getMessage().contains("入库过账"), e.getMessage());
        verify(accrualDao, never()).insert(any(FinAccrual.class));
    }

    @Test
    void vmiRejectsMissingRequiredFields() {
        ServiceException noDoc = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, " ",
                        "sup-x", "供应商", "PO-X", new BigDecimal("10"), new BigDecimal("50")));
        assertTrue(noDoc.getMessage().contains("凭证号"), noDoc.getMessage());

        ServiceException badQty = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, "VC-FIN-001",
                        "sup-x", "供应商", "PO-X", BigDecimal.ZERO, new BigDecimal("50")));
        assertTrue(badQty.getMessage().contains("数量"), badQty.getMessage());

        ServiceException badPrice = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, "VC-FIN-001",
                        "sup-x", "供应商", "PO-X", new BigDecimal("10"), BigDecimal.ZERO));
        assertTrue(badPrice.getMessage().contains("协议价"), badPrice.getMessage());

        ServiceException noSupplier = assertThrows(ServiceException.class, () ->
                accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, "VC-FIN-001",
                        "", "供应商", "PO-X", new BigDecimal("10"), new BigDecimal("50")));
        assertTrue(noSupplier.getMessage().contains("供应商"), noSupplier.getMessage());
        verify(accrualDao, never()).insert(any(FinAccrual.class));
    }

    @Test
    void vmiCreatesAccrualWithAgreementPriceAmount() {
        when(accrualDao.selectMaxSeq(anyString())).thenReturn(null);
        when(accrualDao.insert(any(FinAccrual.class))).thenReturn(1);
        when(accrualDao.updateById(any(FinAccrual.class))).thenReturn(1);
        FinVoucher v = new FinVoucher();
        v.setId("v-vmi");
        when(voucherServiceMock.create(eq(FinVoucher.TYPE_ACCRUAL), any(), anyString(),
                eq("VMI_TRANSFER"), eq("VC-FIN-001"), eq("sup-x"), anyList())).thenReturn(v);

        FinAccrual a = accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, "VC-FIN-001",
                "sup-x", "寄售供应商", "PO-X", new BigDecimal("10"), new BigDecimal("50"));

        ArgumentCaptor<FinAccrual> cap = ArgumentCaptor.forClass(FinAccrual.class);
        verify(accrualDao).insert(cap.capture());
        assertEquals(new BigDecimal("500.00"), cap.getValue().getAmount());
        assertEquals(FinAccrual.SRC_VMI, cap.getValue().getSourceType());
        assertEquals("VC-FIN-001", cap.getValue().getPostingDocNo());
        assertEquals(FinAccrual.ST_OPEN, cap.getValue().getStatus());
        // 暂估凭证分录：借 1403 / 贷 2203
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GlVoucherService.FinVoucherLineSpec>> linesCap =
                ArgumentCaptor.forClass((Class) List.class);
        verify(voucherServiceMock).create(eq(FinVoucher.TYPE_ACCRUAL), any(), anyString(),
                eq("VMI_TRANSFER"), eq("VC-FIN-001"), eq("sup-x"), linesCap.capture());
        List<GlVoucherService.FinVoucherLineSpec> specs = linesCap.getValue();
        assertEquals(2, specs.size());
        assertEquals("1403", specs.get(0).accountCode);
        assertEquals("DR", specs.get(0).direction);
        assertEquals(new BigDecimal("500.00"), specs.get(0).amount);
        assertEquals("2203", specs.get(1).accountCode);
        assertEquals("CR", specs.get(1).direction);
        assertEquals("v-vmi", a.getVoucherId());
    }
}

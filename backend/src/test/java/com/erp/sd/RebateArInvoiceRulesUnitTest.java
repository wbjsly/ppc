package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArItemDao;
import com.erp.dao.fin.ArStatementDao;
import com.erp.dao.fin.ArStatementLineDao;
import com.erp.dao.fin.RedInvoiceDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.sd.InvoiceApplyDao;
import com.erp.dao.sd.RebateBudgetDao;
import com.erp.dao.sd.RebatePolicyDao;
import com.erp.dao.sd.RebateSettlementDao;
import com.erp.dao.sd.RebateTargetDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.crm.ContractPlanDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.sd.InvoiceApply;
import com.erp.entity.sd.RebateBudget;
import com.erp.entity.sd.RebatePolicy;
import com.erp.entity.sd.RebateSettlement;
import com.erp.entity.sd.RebateTarget;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.sd.So;
import com.erp.entity.system.ApprovalInstance;
import com.erp.ops.OutboxPublisher;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.impl.fin.ArServiceImpl;
import com.erp.service.impl.fin.InvoiceServiceImpl;
import com.erp.service.impl.sd.RebateServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17.5 规则单测：返利超额累进（含无上限段）与超预算升级、
 * FIFO 核销（最早应收优先 + 转人工）、按次开票申请与应收金额一致、
 * 红冲余额封顶（spec sales-rebate / sales-invoicing-receivable，FR-4.3-7-x / C-4.3-05）。
 */
class RebateArInvoiceRulesUnitTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                So.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.erp.entity.sd.SoLine.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                ArInvoice.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                InvoiceApply.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.erp.entity.fin.SalesInvoice.class);
    }

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_SALES_MGR"),
                        new SimpleGrantedAuthority("ROLE_SALES_DIRECTOR"),
                        new SimpleGrantedAuthority("ROLE_FINANCE_MGR"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ================= 超额累进计算 =================

    private RebateServiceImpl rebate;
    private RebateTargetDao targetDao;
    private RebatePolicyDao policyDao;
    private RebateBudgetDao budgetDao;
    private RebateSettlementDao settleDao;
    private ArInvoiceDao arDao;
    private ApprovalEngine rebateEngine;
    private SdReturnDao returnDao;
    private MdmCustomerGroupDao customerGroupDao;

    private void initRebate() {
        targetDao = mock(RebateTargetDao.class);
        policyDao = mock(RebatePolicyDao.class);
        budgetDao = mock(RebateBudgetDao.class);
        settleDao = mock(RebateSettlementDao.class);
        arDao = mock(ArInvoiceDao.class);
        rebateEngine = mock(ApprovalEngine.class);
        returnDao = mock(SdReturnDao.class);
        customerGroupDao = mock(MdmCustomerGroupDao.class);
        rebate = new RebateServiceImpl(targetDao, policyDao, budgetDao, settleDao,
                arDao, mock(com.erp.dao.fin.CashWriteoffDao.class), returnDao,
                customerGroupDao, rebateEngine, mock(GlVoucherService.class));
        when(settleDao.selectOne(any())).thenReturn(null);
        when(returnDao.selectList(any())).thenReturn(new ArrayList<>());
        when(settleDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        MdmCustomerGroup cust = new MdmCustomerGroup();
        cust.setId("C1");
        cust.setCustomerCode("CUST-1");
        cust.setCustomerName("测试客户");
        when(customerGroupDao.selectById("C1")).thenReturn(cust);
    }

    private RebatePolicy band(String from, String to, String rate) {
        RebatePolicy p = new RebatePolicy();
        p.setBandFrom(new BigDecimal(from));
        p.setBandTo(to == null ? null : new BigDecimal(to));
        p.setRebateRate(new BigDecimal(rate));
        p.setStatus("EFFECTIVE");
        return p;
    }

    @Test
    void progressiveSegmentsOnlyChargeExcessPortions() {
        initRebate();
        // 目标 400000，基数 520000 → 达成 130%
        RebateTarget t = new RebateTarget();
        t.setTargetAmt(new BigDecimal("400000"));
        when(targetDao.selectOne(any())).thenReturn(t);
        // 三段：0-100 → 0.5%；100-120 → 1%；120-无上限 → 1.5%（组11 修复的无上限段）
        when(policyDao.selectEffective(eq("C1"), any())).thenReturn(new ArrayList<>(List.of(
                band("0", "100", "0.5"), band("100", "120", "1.0"), band("120", null, "1.5"))));
        RebateBudget budget = new RebateBudget();
        budget.setBudgetYear(2026);
        budget.setQ4Amt(new BigDecimal("300000"));
        when(budgetDao.selectOne(any())).thenReturn(budget);
        MdmCustomerGroup cust = new MdmCustomerGroup();
        cust.setId("C1");
        cust.setCustomerCode("CUST-1");
        cust.setCustomerName("测试客户");
        when(arDao.selectQuarterBase(eq("C1"), any(), any())).thenReturn(new BigDecimal("520000"));
        when(settleDao.selectQuarterConsumed(anyString())).thenReturn(BigDecimal.ZERO);
        when(settleDao.selectNosByPrefix(anyString())).thenReturn(List.of());

        Map<String, Object> out = rebate.calculate("C1", "2026Q4");
        @SuppressWarnings("unchecked")
        RebateSettlement s = (RebateSettlement) out.get("settlement");
        // 段1 = 400000×100%×0.5% = 2000；段2 = 400000×20%×1% = 800；段3 = 400000×10%×1.5% = 600
        assertEquals(0, new BigDecimal("3400.00").compareTo(s.getRebateAmt()),
                "超额累进合计应为 3400，实际 " + s.getRebateAmt());
        assertEquals(0, new BigDecimal("1.3000").compareTo(s.getAchieveRate()));
        assertEquals("0", s.getOverBudget());
        // 3400 ≠ 全额 1.5%（520000×1.5% = 7800）→ 证明仅对超额部分计费
        assertTrue(s.getRebateAmt().compareTo(new BigDecimal("7800")) < 0);
    }

    // ================= 超预算升级链 =================

    @Test
    void overBudgetSubmitRequiresReasonPlanAndEscalates() {
        initRebate();
        RebateSettlement s = new RebateSettlement();
        s.setId("S1");
        s.setSettleNo("RB-1");
        s.setStatus(RebateSettlement.ST_DRAFT);
        s.setOverBudget("1");
        s.setCustomerName("测试客户");
        s.setQuarter("2026Q4");
        s.setRebateAmt(new BigDecimal("400000"));
        when(settleDao.selectById("S1")).thenReturn(s);
        ApprovalInstance inst = new ApprovalInstance();
        inst.setId("AP-1");
        when(rebateEngine.submit(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(inst);

        // 缺说明 422
        ServiceException noReason = assertThrows(ServiceException.class,
                () -> rebate.submit("S1", null, "平衡方案"));
        assertTrue(noReason.getMessage().contains("原因说明"), noReason.getMessage());
        // 缺平衡方案 422
        ServiceException noPlan = assertThrows(ServiceException.class,
                () -> rebate.submit("S1", "超预算说明", null));
        assertTrue(noPlan.getMessage().contains("平衡方案"), noPlan.getMessage());

        // 双说明齐 → 两级链（主管 + 总监）升级
        rebate.submit("S1", "四季度大单集中确认", "动用年度预算结余并压缩 Q1 预留");
        ArgumentCaptor<List> chain = ArgumentCaptor.forClass(List.class);
        verify(rebateEngine).submit(eq("Rebate"), eq("S1"), anyString(), eq(null), chain.capture());
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> ch = chain.getValue();
        assertEquals(2, ch.size()); // 主管 → 总监（C-4.3-05 升级）
        assertEquals(RebateSettlement.ST_APPROVING, s.getStatus());
    }

    // ================= FIFO 核销 =================

    private ArServiceImpl arService;
    private ArInvoiceDao arInvDao;
    private com.erp.dao.fin.CashWriteoffDao woDao;

    private ArInvoice ar(String id, String no, String amt, String paid) {
        ArInvoice a = new ArInvoice();
        a.setId(id);
        a.setArNo(no);
        a.setCustomerId("C1");
        a.setAmount(new BigDecimal(amt));
        a.setPaidAmount(new BigDecimal(paid));
        a.setRedAmount(BigDecimal.ZERO);
        a.setStatus("UNPAID");
        a.setInvoiceDate(LocalDate.of(2026, 1, 1));
        return a;
    }

    private GlVoucherService voucherService;

    private void initAr() {
        arInvDao = mock(ArInvoiceDao.class);
        woDao = mock(com.erp.dao.fin.CashWriteoffDao.class);
        voucherService = mock(GlVoucherService.class);
        com.erp.entity.fin.FinVoucher v = new com.erp.entity.fin.FinVoucher();
        v.setId("V1");
        v.setVoucherNo("VCH-1");
        when(voucherService.create(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(v);
        // add-payment-collection：registerReceipt 委托统一中心，构造参数含 cash 服务
        arService = new ArServiceImpl(arInvDao, mock(ArItemDao.class), woDao,
                mock(com.erp.service.fin.CashDocService.class),
                mock(com.erp.service.fin.CashWriteoffService.class),
                mock(ArStatementDao.class), mock(ArStatementLineDao.class),
                mock(ContractDao.class), mock(ContractPlanDao.class), mock(SoDao.class),
                voucherService, mock(com.erp.dao.fin.FinBankAccountDao.class));
        when(arInvDao.updateById(any(ArInvoice.class))).thenReturn(1);
    }

    @Test
    void fifoSettlesOldestArFirst() {
        // add-payment-collection：FIFO 语义迁至统一核销内核 CashWriteoffServiceImpl.settle
        var arDao = mock(ArInvoiceDao.class);
        var woDao2 = mock(com.erp.dao.fin.CashWriteoffDao.class);
        var docDao = mock(com.erp.dao.fin.CashDocDao.class);
        var invoiceDao = mock(com.erp.dao.fin.FinApInvoiceDao.class);
        var engine = mock(ApprovalEngine.class);
        var settle = new com.erp.service.impl.fin.CashWriteoffServiceImpl(
                docDao, woDao2, arDao, invoiceDao);

        ArInvoice ar1 = ar("A1", "AR-1", "50000", "0");
        ArInvoice ar2 = ar("A2", "AR-2", "30000", "0");
        // 同期（202610）：避免触发跨期挂审批分支
        ar1.setInvoiceDate(LocalDate.of(2026, 10, 1));
        ar2.setInvoiceDate(LocalDate.of(2026, 10, 2));
        when(arDao.selectFifoPool("C1")).thenReturn(List.of(ar1, ar2));

        var doc = new com.erp.entity.fin.CashDoc();
        doc.setId("D1");
        doc.setDocNo("RC20261007-0001");
        doc.setDirection("IN");
        doc.setPartyId("C1");
        doc.setAmount(new BigDecimal("60000"));
        doc.setPayDate(LocalDate.of(2026, 10, 7));
        doc.setCurrency("CNY");

        Map<String, Object> out = settle.settle(doc, null);
        // 最早应收先清：AR-1 吃满 50000，AR-2 部分 10000，无剩余转预收
        assertEquals(0, new BigDecimal("60000").compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("remaining")));
        assertEquals(2, out.get("rowCount"));
        assertEquals(false, out.get("crossPeriod"));
        // 核销行落 cash_writeoff（FIFO 顺序：AR-1 在前）
        org.mockito.ArgumentCaptor<com.erp.entity.fin.CashWriteoff> captor =
                org.mockito.ArgumentCaptor.forClass(com.erp.entity.fin.CashWriteoff.class);
        verify(woDao2, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals("AR-1", captor.getAllValues().get(0).getTargetNo());
        assertEquals(0, new BigDecimal("50000").compareTo(captor.getAllValues().get(0).getAmount()));
        assertEquals("AR-2", captor.getAllValues().get(1).getTargetNo());
        assertEquals(0, new BigDecimal("10000").compareTo(captor.getAllValues().get(1).getAmount()));
    }

    @Test
    void settleWithoutOpenArLeavesPrepaymentCredit() {
        // 无未清应收 → 核销 0，剩余全额转预收（收款单过账时贷 2205）
        var arDao = mock(ArInvoiceDao.class);
        var woDao2 = mock(com.erp.dao.fin.CashWriteoffDao.class);
        var settle = new com.erp.service.impl.fin.CashWriteoffServiceImpl(
                mock(com.erp.dao.fin.CashDocDao.class), woDao2, arDao,
                mock(com.erp.dao.fin.FinApInvoiceDao.class));
        when(arDao.selectFifoPool("C1")).thenReturn(List.of());

        var doc = new com.erp.entity.fin.CashDoc();
        doc.setId("D2");
        doc.setDocNo("RC20261007-0002");
        doc.setDirection("IN");
        doc.setPartyId("C1");
        doc.setAmount(new BigDecimal("3000"));
        doc.setPayDate(LocalDate.now());
        doc.setCurrency("CNY");

        Map<String, Object> out = settle.settle(doc, null);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, new BigDecimal("3000").compareTo((BigDecimal) out.get("remaining")));
        assertEquals(0, out.get("rowCount"));
        verify(woDao2, org.mockito.Mockito.never()).insert(any());
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }

    // ================= 按次开票金额一致性 =================

    @Test
    void shipmentConfirmCreatesApplyAndArWithSameAmount() {
        InvoiceApplyDao applyDao = mock(InvoiceApplyDao.class);
        SalesInvoiceDao invoiceDao = mock(SalesInvoiceDao.class);
        ArInvoiceDao finArDao = mock(ArInvoiceDao.class);
        ShipmentLineDao shipLineDao = mock(ShipmentLineDao.class);
        SoLineDao soLineDao = mock(SoLineDao.class);
        com.erp.dao.mdm.MdmTaxCodeDao taxCodeDao = mock(com.erp.dao.mdm.MdmTaxCodeDao.class);
        InvoiceServiceImpl invoiceService = new InvoiceServiceImpl(applyDao, invoiceDao,
                mock(RedInvoiceDao.class), finArDao, mock(ArItemDao.class),
                mock(ShipmentDao.class), shipLineDao, mock(SoDao.class), soLineDao,
                mock(MdmCustomerGroupDao.class), mock(com.erp.dao.mdm.MdmItemDao.class),
                taxCodeDao, mock(com.erp.service.SysParamService.class),
                mock(OutboxPublisher.class),
                mock(com.erp.service.fin.FxRateService.class));

        when(applyDao.selectList(any())).thenReturn(new ArrayList<>());
        ShipmentLine sl = new ShipmentLine();
        sl.setId("SL1");
        sl.setShipId("SH1");
        sl.setLineNo(1);
        sl.setSoId(null); // 跨 SO 合并 → 不回挂
        sl.setItemCode("RM1");
        sl.setQty(new BigDecimal("200"));
        when(shipLineDao.selectList(any())).thenReturn(List.of(sl));
        when(applyDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(finArDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        com.erp.entity.mdm.MdmTaxCode tc = new com.erp.entity.mdm.MdmTaxCode();
        tc.setTaxCode("VAT13");
        tc.setTaxRate(new BigDecimal("0.13"));
        when(taxCodeDao.selectHit(anyString(), any())).thenReturn(tc);
        when(applyDao.insert(any(InvoiceApply.class))).thenReturn(1);
        when(finArDao.insert(any(ArInvoice.class))).thenReturn(1);
        when(applyDao.updateById(any(InvoiceApply.class))).thenReturn(1);

        Shipment ship = new Shipment();
        ship.setId("SH1");
        ship.setShipNo("SH-T1");
        ship.setCustomerId("C1");
        ship.setCustomerCode("CUST-1");
        ship.setCustomerName("测试客户");
        ship.setTotalAmt(new BigDecimal("20000"));

        InvoiceApply apply = invoiceService.onShipmentConfirmed(ship);
        // 申请金额 = 发货额（按次开票一一对应，FR-4.3-7-1）
        assertEquals(0, new BigDecimal("20000").compareTo(apply.getApplyAmount()));
        assertEquals(0, new BigDecimal("20000").compareTo(apply.getShipAmount()));
        // 应收与申请同额（一致性）
        ArgumentCaptor<ArInvoice> arCap = ArgumentCaptor.forClass(ArInvoice.class);
        verify(finArDao).insert(arCap.capture());
        assertEquals(0, new BigDecimal("20000").compareTo(arCap.getValue().getAmount()));
        assertEquals(apply.getArNo(), arCap.getValue().getArNo());

        // 幂等：同发货单再来一次返回原申请
        when(applyDao.selectList(any())).thenReturn(List.of(apply));
        InvoiceApply again = invoiceService.onShipmentConfirmed(ship);
        assertEquals(apply.getId(), again.getId());
    }

    // ================= 外币应收记账汇率固化（add-fx-revaluation 决策 A）=================

    private InvoiceServiceImpl arServiceWithFx(ArInvoiceDao finArDao, InvoiceApplyDao applyDao,
                                               ShipmentLineDao shipLineDao, SoLineDao soLineDao,
                                               com.erp.dao.mdm.MdmTaxCodeDao taxCodeDao,
                                               SoDao soDao,
                                               com.erp.service.fin.FxRateService fx) {
        return new InvoiceServiceImpl(applyDao, mock(SalesInvoiceDao.class),
                mock(RedInvoiceDao.class), finArDao, mock(ArItemDao.class),
                mock(ShipmentDao.class), shipLineDao, soDao, soLineDao,
                mock(MdmCustomerGroupDao.class), mock(com.erp.dao.mdm.MdmItemDao.class),
                taxCodeDao, mock(com.erp.service.SysParamService.class),
                mock(OutboxPublisher.class), fx);
    }

    private Shipment usdShip(String soId) {
        Shipment ship = new Shipment();
        ship.setId("SH-FX");
        ship.setShipNo("SH-FX-1");
        ship.setCustomerId("C1");
        ship.setCustomerCode("CUST-1");
        ship.setCustomerName("外币客户");
        ship.setTotalAmt(new BigDecimal("10000"));
        return ship;
    }

    @Test
    void usdArFixesBookingRate() {
        InvoiceApplyDao applyDao = mock(InvoiceApplyDao.class);
        ArInvoiceDao finArDao = mock(ArInvoiceDao.class);
        ShipmentLineDao shipLineDao = mock(ShipmentLineDao.class);
        SoLineDao soLineDao = mock(SoLineDao.class);
        com.erp.dao.mdm.MdmTaxCodeDao taxCodeDao = mock(com.erp.dao.mdm.MdmTaxCodeDao.class);
        SoDao soDao = mock(SoDao.class);
        com.erp.service.fin.FxRateService fx = mock(com.erp.service.fin.FxRateService.class);

        So so = new So();
        so.setId("SO-USD");
        so.setCurrency("USD");
        when(soDao.selectById("SO-USD")).thenReturn(so);
        when(fx.requireMiddleRate(eq("USD"), eq("CNY"), any())).thenReturn(new BigDecimal("7.120000"));

        InvoiceServiceImpl svc = arServiceWithFx(finArDao, applyDao, shipLineDao, soLineDao,
                taxCodeDao, soDao, fx);
        when(applyDao.selectList(any())).thenReturn(new ArrayList<>());
        ShipmentLine sl = new ShipmentLine();
        sl.setId("SL-FX");
        sl.setShipId("SH-FX");
        sl.setLineNo(1);
        sl.setSoId("SO-USD");
        sl.setItemCode("RM1");
        sl.setQty(new BigDecimal("10"));
        when(shipLineDao.selectList(any())).thenReturn(List.of(sl));
        when(applyDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(finArDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        com.erp.entity.mdm.MdmTaxCode tc = new com.erp.entity.mdm.MdmTaxCode();
        tc.setTaxCode("VAT13");
        tc.setTaxRate(new BigDecimal("0.13"));
        when(taxCodeDao.selectHit(anyString(), any())).thenReturn(tc);
        when(applyDao.insert(any(InvoiceApply.class))).thenReturn(1);
        when(finArDao.insert(any(ArInvoice.class))).thenReturn(1);
        when(applyDao.updateById(any(InvoiceApply.class))).thenReturn(1);

        svc.onShipmentConfirmed(usdShip("SO-USD"));
        ArgumentCaptor<ArInvoice> arCap = ArgumentCaptor.forClass(ArInvoice.class);
        verify(finArDao).insert(arCap.capture());
        assertEquals("USD", arCap.getValue().getCurrency(), "应收币种取 SO 交易币种");
        assertEquals(0, new BigDecimal("7.120000").compareTo(arCap.getValue().getBookingRate()),
                "外币应收按开票日中间价固化记账汇率");
    }

    @Test
    void usdArMissingRateBlocks() {
        InvoiceApplyDao applyDao = mock(InvoiceApplyDao.class);
        ArInvoiceDao finArDao = mock(ArInvoiceDao.class);
        ShipmentLineDao shipLineDao = mock(ShipmentLineDao.class);
        SoLineDao soLineDao = mock(SoLineDao.class);
        com.erp.dao.mdm.MdmTaxCodeDao taxCodeDao = mock(com.erp.dao.mdm.MdmTaxCodeDao.class);
        SoDao soDao = mock(SoDao.class);
        com.erp.service.fin.FxRateService fx = mock(com.erp.service.fin.FxRateService.class);

        So so = new So();
        so.setId("SO-JPY");
        so.setCurrency("JPY");
        when(soDao.selectById("SO-JPY")).thenReturn(so);
        when(fx.requireMiddleRate(eq("JPY"), eq("CNY"), any()))
                .thenThrow(new com.erp.common.ServiceException(422, "汇率主数据缺失"));

        InvoiceServiceImpl svc = arServiceWithFx(finArDao, applyDao, shipLineDao, soLineDao,
                taxCodeDao, soDao, fx);
        when(applyDao.selectList(any())).thenReturn(new ArrayList<>());
        ShipmentLine sl = new ShipmentLine();
        sl.setId("SL-JPY");
        sl.setShipId("SH-FX");
        sl.setLineNo(1);
        sl.setSoId("SO-JPY");
        sl.setItemCode("RM1");
        sl.setQty(new BigDecimal("10"));
        when(shipLineDao.selectList(any())).thenReturn(List.of(sl));
        when(applyDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(finArDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        com.erp.entity.mdm.MdmTaxCode tc = new com.erp.entity.mdm.MdmTaxCode();
        tc.setTaxCode("VAT13");
        tc.setTaxRate(new BigDecimal("0.13"));
        when(taxCodeDao.selectHit(anyString(), any())).thenReturn(tc);
        when(applyDao.insert(any(InvoiceApply.class))).thenReturn(1);

        com.erp.common.ServiceException ex = assertThrows(com.erp.common.ServiceException.class,
                () -> svc.onShipmentConfirmed(usdShip("SO-JPY")));
        assertTrue(ex.getMessage().contains("汇率主数据缺失"), "缺汇率 L1 阻断");
    }

    @Test
    void cnyArBookingRateOneNoFxQuery() {
        InvoiceApplyDao applyDao = mock(InvoiceApplyDao.class);
        ArInvoiceDao finArDao = mock(ArInvoiceDao.class);
        ShipmentLineDao shipLineDao = mock(ShipmentLineDao.class);
        SoLineDao soLineDao = mock(SoLineDao.class);
        com.erp.dao.mdm.MdmTaxCodeDao taxCodeDao = mock(com.erp.dao.mdm.MdmTaxCodeDao.class);
        SoDao soDao = mock(SoDao.class);
        com.erp.service.fin.FxRateService fx = mock(com.erp.service.fin.FxRateService.class);

        So so = new So();
        so.setId("SO-CNY");
        so.setCurrency("CNY");
        when(soDao.selectById("SO-CNY")).thenReturn(so);

        InvoiceServiceImpl svc = arServiceWithFx(finArDao, applyDao, shipLineDao, soLineDao,
                taxCodeDao, soDao, fx);
        when(applyDao.selectList(any())).thenReturn(new ArrayList<>());
        ShipmentLine sl = new ShipmentLine();
        sl.setId("SL-CNY");
        sl.setShipId("SH-FX");
        sl.setLineNo(1);
        sl.setSoId("SO-CNY");
        sl.setItemCode("RM1");
        sl.setQty(new BigDecimal("10"));
        when(shipLineDao.selectList(any())).thenReturn(List.of(sl));
        when(applyDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(finArDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        com.erp.entity.mdm.MdmTaxCode tc = new com.erp.entity.mdm.MdmTaxCode();
        tc.setTaxCode("VAT13");
        tc.setTaxRate(new BigDecimal("0.13"));
        when(taxCodeDao.selectHit(anyString(), any())).thenReturn(tc);
        when(applyDao.insert(any(InvoiceApply.class))).thenReturn(1);
        when(finArDao.insert(any(ArInvoice.class))).thenReturn(1);
        when(applyDao.updateById(any(InvoiceApply.class))).thenReturn(1);

        svc.onShipmentConfirmed(usdShip("SO-CNY"));
        ArgumentCaptor<ArInvoice> arCap = ArgumentCaptor.forClass(ArInvoice.class);
        verify(finArDao).insert(arCap.capture());
        assertEquals("CNY", arCap.getValue().getCurrency(), "应收币种 CNY");
        assertEquals(0, BigDecimal.ONE.compareTo(arCap.getValue().getBookingRate()),
                "CNY 应收记账汇率置 1");
        verify(fx, org.mockito.Mockito.never()).requireMiddleRate(anyString(), anyString(), any());
    }

    // ================= 红冲余额 =================

    @Test
    void redInvoiceCannotExceedRemainingBalance() {
        InvoiceApplyDao applyDao = mock(InvoiceApplyDao.class);
        SalesInvoiceDao invoiceDao = mock(SalesInvoiceDao.class);
        RedInvoiceDao redDao = mock(RedInvoiceDao.class);
        ArInvoiceDao finArDao = mock(ArInvoiceDao.class);
        InvoiceServiceImpl invoiceService = new InvoiceServiceImpl(applyDao, invoiceDao,
                redDao, finArDao, mock(ArItemDao.class), mock(ShipmentDao.class),
                mock(ShipmentLineDao.class), mock(SoDao.class), mock(SoLineDao.class),
                mock(MdmCustomerGroupDao.class), mock(com.erp.dao.mdm.MdmItemDao.class),
                mock(com.erp.dao.mdm.MdmTaxCodeDao.class),
                mock(com.erp.service.SysParamService.class), mock(OutboxPublisher.class),
                mock(com.erp.service.fin.FxRateService.class));

        com.erp.entity.fin.SalesInvoice origin = new com.erp.entity.fin.SalesInvoice();
        origin.setId("INV1");
        origin.setInvoiceNo("FP-1");
        origin.setAmount(new BigDecimal("10000"));
        origin.setRedAmount(BigDecimal.ZERO);
        origin.setArId("A1");
        when(invoiceDao.selectById("INV1")).thenReturn(origin);
        when(redDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(redDao.insert(any(com.erp.entity.fin.RedInvoice.class))).thenReturn(1);
        when(invoiceDao.updateById(any(com.erp.entity.fin.SalesInvoice.class))).thenReturn(1);
        ArInvoice ar = ar("A1", "AR-1", "10000", "0");
        when(finArDao.selectById("A1")).thenReturn(ar);
        when(finArDao.updateById(any(ArInvoice.class))).thenReturn(1);
        when(applyDao.selectList(any())).thenReturn(new ArrayList<>());

        // 红冲 3000 → 原票/应收累计红冲回写
        invoiceService.redInvoice("INV1", null, null, new BigDecimal("3000"), "退货红冲");
        assertEquals(0, new BigDecimal("3000").compareTo(origin.getRedAmount()));
        assertEquals(0, new BigDecimal("3000").compareTo(ar.getRedAmount()));

        // 再红冲 8000 > 剩余 7000 → 422 封顶
        ServiceException e = assertThrows(ServiceException.class,
                () -> invoiceService.redInvoice("INV1", null, null, new BigDecimal("8000"), "超额"));
        assertTrue(e.getMessage().contains("可冲余额"), e.getMessage());
    }
}

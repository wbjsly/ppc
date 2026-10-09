package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArItemDao;
import com.erp.dao.fin.ArReceiptDao;
import com.erp.dao.fin.ArStatementDao;
import com.erp.dao.fin.ArStatementLineDao;
import com.erp.dao.fin.ArWriteoffDao;
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
import com.erp.entity.fin.ArReceipt;
import com.erp.entity.fin.ArWriteoff;
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
                arDao, mock(ArWriteoffDao.class), returnDao,
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
    private ArWriteoffDao woDao;
    private ArReceiptDao receiptDao;

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
        woDao = mock(ArWriteoffDao.class);
        receiptDao = mock(ArReceiptDao.class);
        voucherService = mock(GlVoucherService.class);
        com.erp.entity.fin.FinVoucher v = new com.erp.entity.fin.FinVoucher();
        v.setId("V1");
        v.setVoucherNo("VCH-1");
        when(voucherService.create(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(v);
        arService = new ArServiceImpl(arInvDao, mock(ArItemDao.class), woDao, receiptDao,
                mock(ArStatementDao.class), mock(ArStatementLineDao.class),
                mock(ContractDao.class), mock(ContractPlanDao.class), mock(SoDao.class),
                voucherService);
        when(receiptDao.selectNosByPrefix(anyString())).thenReturn(List.of());
        when(arInvDao.updateById(any(ArInvoice.class))).thenReturn(1);
    }

    @Test
    void fifoMatchesOldestReceivablesFirst() {
        initAr();
        ArInvoice ar1 = ar("A1", "AR-1", "50000", "0");
        ArInvoice ar2 = ar("A2", "AR-2", "30000", "0");
        when(arInvDao.selectList(any())).thenReturn(List.of(ar1)); // 客户信息 probe
        when(arInvDao.selectFifoPool("C1")).thenReturn(List.of(ar1, ar2));

        Map<String, Object> out = arService.registerReceipt("C1", new BigDecimal("60000"),
                LocalDate.of(2026, 10, 7), "测试回款");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) out.get("details");
        // 最早应收先清：AR-1 吃满 50000，AR-2 部分 10000
        assertEquals(2, details.size());
        assertEquals("AR-1", details.get(0).get("arNo"));
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) details.get(0).get("amount")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) details.get(0).get("balance")));
        assertEquals("AR-2", details.get(1).get("arNo"));
        assertEquals(0, new BigDecimal("10000").compareTo((BigDecimal) details.get(1).get("amount")));
        assertEquals(0, new BigDecimal("20000").compareTo((BigDecimal) details.get(1).get("balance")));
        // 回款全额匹配 → AUTO
        ArReceipt receipt = (ArReceipt) out.get("receipt");
        assertEquals(ArReceipt.ST_AUTO, receipt.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("unmatched")));
        // 核销逐笔落 writeoff（两笔 = 两条核销记录）
        verify(woDao, org.mockito.Mockito.times(2)).insert(any(ArWriteoff.class));
    }

    @Test
    void receiptWithoutOpenArGoesManualQueue() {
        initAr();
        when(arInvDao.selectList(any())).thenReturn(List.of());
        when(arInvDao.selectFifoPool("C1")).thenReturn(List.of());
        Map<String, Object> out = arService.registerReceipt("C1", new BigDecimal("3000"),
                null, null);
        ArReceipt receipt = (ArReceipt) out.get("receipt");
        assertEquals(ArReceipt.ST_PENDING, receipt.getStatus());
        assertNotNull(out.get("manualHint"));
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
                mock(OutboxPublisher.class));

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
                mock(com.erp.service.SysParamService.class), mock(OutboxPublisher.class));

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

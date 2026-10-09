package com.erp.vmi;

import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.dao.vmi.VmiAgreementDao;
import com.erp.dao.vmi.VmiAgreementLineDao;
import com.erp.dao.vmi.VmiAlertDao;
import com.erp.dao.vmi.VmiDisposalDao;
import com.erp.dao.vmi.VmiSettlementDao;
import com.erp.dao.vmi.VmiSettlementLineDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.vmi.VmiAgreement;
import com.erp.entity.vmi.VmiAgreementLine;
import com.erp.entity.vmi.VmiDisposal;
import com.erp.entity.vmi.VmiSettlement;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.fin.AccrualService;
import com.erp.service.impl.vmi.MaterialIssueServiceImpl;
import com.erp.service.impl.vmi.VmiAgreementServiceImpl;
import com.erp.service.impl.vmi.VmiAlertServiceImpl;
import com.erp.service.impl.vmi.VmiDisposalServiceImpl;
import com.erp.service.impl.vmi.VmiSettlementServiceImpl;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 寄售采购服务层规则单测（spec vmi-consignment / material-issue，tasks 9.2）：
 * 价格条款有效性边界（C-4.2-09）、水位双卡与确认放行（BR-4.2-36）、
 * 账龄处置扫描（BR-4.2-39）、结算双确认状态机（BR-4.2-38）、FIFO 配批与不足阻断。
 * 数据库侧语义（条件扣减/暂估生成/分页）见 tvmi_test.py 集成套件。
 */
class VmiRulesUnitTest {

    @BeforeEach
    void loginAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ---------- C-4.2-09 价格条款边界 ----------

    @Test
    void priceLineOnRespectsWindow() {
        VmiAgreementDao agreeDao = mock(VmiAgreementDao.class);
        VmiAgreementLineDao lineDao = mock(VmiAgreementLineDao.class);
        VmiAgreementServiceImpl svc = new VmiAgreementServiceImpl(agreeDao, lineDao,
                mock(MdmSupplierDao.class));

        VmiAgreement ag = new VmiAgreement();
        ag.setId("ag-1");
        ag.setStatus(VmiAgreement.ST_EFFECTIVE);
        when(agreeDao.selectList(any())).thenReturn(List.of(ag));

        VmiAgreementLine line = new VmiAgreementLine();
        line.setAgreeId("ag-1");
        line.setItemCode("RM1");
        line.setUnitPrice(new BigDecimal("9.5"));
        line.setPriceStart(LocalDate.now().minusDays(5));
        line.setPriceEnd(LocalDate.now().plusDays(5));
        when(lineDao.selectList(any())).thenReturn(List.of(line));

        assertNotNull(svc.priceLineOn("sup-1", "RM1", LocalDate.now()), "窗口内应命中协议价");

        line.setPriceEnd(LocalDate.now().minusDays(1));
        assertNull(svc.priceLineOn("sup-1", "RM1", LocalDate.now()), "窗口外须返回 null（C-4.2-09 阻断前提）");

        line.setPriceEnd(LocalDate.now().plusDays(5));
        assertNull(svc.priceLineOn("sup-1", "RM1", LocalDate.now().plusDays(10)),
                "领用日晚于协议失效日须返回 null");
    }

    // ---------- BR-4.2-36 水位双卡与确认放行 ----------

    private VmiAlertServiceImpl alertService(VmiAlertDao alertDao, VmiStockDao stockDao,
                                             VmiAgreementService agreement) {
        return new VmiAlertServiceImpl(alertDao, stockDao, mock(VmiAgreementDao.class),
                mock(VmiAgreementLineDao.class), agreement,
                mock(com.erp.service.portal.PortalEventService.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void waterLevelBlocksOverMax() {
        VmiAgreementService agreement = mock(VmiAgreementService.class);
        VmiAgreementLine line = new VmiAgreementLine();
        line.setAgreeId("ag-1");
        line.setItemCode("RM1");
        line.setMaxQty(new BigDecimal("100"));
        when(agreement.lineFor(anyString(), anyString())).thenReturn(line);

        VmiStockDao stockDao = mock(VmiStockDao.class);
        VmiStock s = new VmiStock();
        s.setQty(new BigDecimal("90"));
        when(stockDao.selectList(any())).thenReturn(List.of(s));

        VmiAlertDao alertDao = mock(VmiAlertDao.class);
        when(alertDao.selectOne(any())).thenReturn(null);
        VmiAgreementDao agreeDao = mock(VmiAgreementDao.class);
        when(agreeDao.selectById(anyString())).thenReturn(new VmiAgreement());
        // buildOpenWaterAlert 内部走注入的 agreeDao —— 通过反射换入 mock
        VmiAlertServiceImpl svc = alertService(alertDao, stockDao, agreement);
        ReflectionTestUtils.setField(svc, "agreeDao", agreeDao);

        Map<String, BigDecimal> incoming = new HashMap<>();
        incoming.put("RM1", new BigDecimal("20"));   // 90 + 20 > 100

        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.assertWaterLevel("sup-1", incoming, false));
        assertTrue(e.getMessage().contains("水位"), e.getMessage());

        // confirm=true 且已有 CONFIRMED 告警 → 放行
        when(alertDao.selectCount(any())).thenReturn(1L);
        assertDoesNotThrow(() -> svc.assertWaterLevel("sup-1", incoming, true), "已确认应放行");

        // 水位内不阻断
        incoming.put("RM1", new BigDecimal("5"));
        assertDoesNotThrow(() -> svc.assertWaterLevel("sup-1", incoming, false));
    }

    // ---------- BR-4.2-39 账龄处置扫描 ----------

    @Test
    void disposalScanAgingRule() {
        VmiDisposalDao disposalDao = mock(VmiDisposalDao.class);
        VmiStockDao stockDao = mock(VmiStockDao.class);

        VmiStock aged = new VmiStock();
        aged.setId("st-aged");
        aged.setItemCode("RM1");
        aged.setBatchNo("B-OLD");
        aged.setSupplierId("sup-1");
        aged.setQty(new BigDecimal("30"));
        aged.setIssuedQty(BigDecimal.ZERO);
        aged.setInboundDate(LocalDate.now().minusDays(219));   // > 180

        VmiStock used = new VmiStock();
        used.setId("st-used");
        used.setItemCode("RM1");
        used.setBatchNo("B-USED");
        used.setSupplierId("sup-1");
        used.setQty(new BigDecimal("30"));
        used.setIssuedQty(new BigDecimal("10"));               // 有领用 → 不生成
        used.setInboundDate(LocalDate.now().minusDays(300));

        VmiStock fresh = new VmiStock();
        fresh.setId("st-fresh");
        fresh.setItemCode("RM1");
        fresh.setBatchNo("B-FRESH");
        fresh.setSupplierId("sup-1");
        fresh.setQty(new BigDecimal("30"));
        fresh.setIssuedQty(BigDecimal.ZERO);
        fresh.setInboundDate(LocalDate.now().minusDays(10));   // 未超期

        when(stockDao.selectList(any())).thenReturn(List.of(aged, used, fresh));
        when(disposalDao.selectCount(any())).thenReturn(0L);

        VmiDisposalServiceImpl svc = new VmiDisposalServiceImpl(disposalDao, stockDao);
        ReflectionTestUtils.setField(svc, "agingLimitDays", 180);

        int created = svc.scan();
        assertEquals(1, created, "仅超期且零领用批次生成建议单");
        verify(disposalDao, times(1)).insert(any(VmiDisposal.class));

        // 去重：已有 OPEN 建议单不重复生成
        when(disposalDao.selectCount(any())).thenReturn(1L);
        assertEquals(0, svc.scan(), "同批次 OPEN 建议单去重");
    }

    // ---------- BR-4.2-38 结算双确认状态机 ----------

    private VmiSettlementServiceImpl settlementService(VmiSettlementDao settleDao) {
        VmiSettlementServiceImpl svc = new VmiSettlementServiceImpl(
                settleDao, mock(VmiSettlementLineDao.class), mock(MaterialIssueDao.class),
                mock(MaterialIssueLineDao.class), mock(VmiAgreementDao.class),
                mock(MdmSupplierDao.class), mock(VmiAgreementService.class),
                mock(VmiAlertService.class), mock(com.erp.service.portal.PortalEventService.class));
        ReflectionTestUtils.setField(svc, "tolerance", new BigDecimal("0.005"));
        return svc;
    }

    private VmiSettlement head(String status, String rate) {
        VmiSettlement s = new VmiSettlement();
        s.setId("set-1");
        s.setSettleNo("VS202610-000001");
        s.setSupplierId("sup-1");
        s.setStatus(status);
        s.setDiffRate(new BigDecimal(rate));
        return s;
    }

    @Test
    void settlementDoubleSignStateMachine() {
        VmiSettlementDao settleDao = mock(VmiSettlementDao.class);
        VmiSettlementServiceImpl svc = settlementService(settleDao);

        // 超容差：PM 单签保持 ON_HOLD，双签齐备才 CONFIRMED
        VmiSettlement onHold = head(VmiSettlement.ST_ON_HOLD, "0.02");
        when(settleDao.selectById("set-1")).thenReturn(onHold);
        Map<String, Object> r1 = svc.sign("set-1", Map.of("side", "pm"));
        assertEquals(VmiSettlement.ST_ON_HOLD,
                ((VmiSettlement) r1.get("settlement")).getStatus(), "超容差单签须保持挂起");
        assertFalse((Boolean) r1.get("confirmed"));

        Map<String, Object> r2 = svc.sign("set-1",
                Map.of("side", "supplier", "supplierConfirmBy", "王五", "way", "邮件回签"));
        assertEquals(VmiSettlement.ST_CONFIRMED,
                ((VmiSettlement) r2.get("settlement")).getStatus(), "两签齐备转 CONFIRMED");
        assertTrue((Boolean) r2.get("confirmed"));

        // 已确认再签 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.sign("set-1", Map.of("side", "pm")));
        assertTrue(e.getMessage().contains("已确认"), e.getMessage());

        // 容差内：PM 单签直接 CONFIRMED
        VmiSettlement draft = head(VmiSettlement.ST_DRAFT, "0.001");
        when(settleDao.selectById("set-2")).thenReturn(draft);
        draft.setId("set-2");
        Map<String, Object> r3 = svc.sign("set-2", Map.of("side", "pm"));
        assertEquals(VmiSettlement.ST_CONFIRMED,
                ((VmiSettlement) r3.get("settlement")).getStatus(), "容差内 PM 签即确认");

        // 非法 side 422（用未确认的新单，避开"已确认"分支）
        VmiSettlement freshHead = head(VmiSettlement.ST_DRAFT, "0.001");
        freshHead.setId("set-3");
        when(settleDao.selectById("set-3")).thenReturn(freshHead);
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> svc.sign("set-3", Map.of("side", "boss")));
        assertTrue(e2.getMessage().contains("side"), e2.getMessage());
    }

    // ---------- material-issue FIFO 配批与不足阻断 ----------

    private MaterialIssueServiceImpl issueService(InvStockDao stockDao) {
        // 真实引擎共享 stockDao：配批/扣减断言穿引擎继续生效（type 由桩按需返回）
        com.erp.dao.inv.InvDocTypeDao docTypeDao = mock(com.erp.dao.inv.InvDocTypeDao.class);
        org.mockito.Mockito.when(docTypeDao.selectByCode(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    com.erp.entity.inv.InvDocType t = new com.erp.entity.inv.InvDocType();
                    String code = inv.getArgument(0);
                    t.setTypeCode(code);
                    t.setDirection("VMI_TRANSFER_IN".equals(code) ? "IN" : "OUT");
                    t.setTypeName("test");
                    t.setEnabled(1);
                    t.setNeedBatch(1);
                    t.setNeedSerial(0);
                    return t;
                });
        com.erp.service.inv.StockPostingEngine engine =
                new com.erp.service.impl.inv.StockPostingEngineImpl(
                        docTypeDao, stockDao, mock(com.erp.dao.inv.InvBatchDao.class),
                        mock(com.erp.dao.inv.InvTransactionDao.class),
                        mock(com.erp.dao.inv.InvSerialDao.class),
                        mock(com.erp.dao.sd.ReservationDao.class),
                        mock(com.erp.dao.mdm.MdmItemDao.class),
                        mock(com.erp.ops.OutboxPublisher.class),
                        sysParamMock());
        return new MaterialIssueServiceImpl(mock(MaterialIssueDao.class),
                mock(MaterialIssueLineDao.class), stockDao, mock(VmiStockDao.class),
                mock(MdmSupplierDao.class), mock(VmiAgreementService.class),
                mock(VmiAlertService.class), mock(AccrualService.class), engine,
                mock(com.erp.service.inv.FifoStrategyService.class), mock(com.erp.service.inv.PickTaskGate.class), mock(com.erp.service.inv.PickTaskService.class));
    }

    private InvStock stockRow(String id, String batch, String avail, LocalDateTime create) {
        InvStock s = new InvStock();
        s.setId(id);
        s.setItemCode("RM1");
        s.setItemName("物料一");
        s.setBatchNo(batch);
        s.setAvailableQty(new BigDecimal(avail));
        s.setQty(new BigDecimal(avail));
        ReflectionTestUtils.setField(s, "createDate", create);
        return s;
    }

    @Test
    void fifoPlanSplitsByOldestBatch() {
        InvStockDao stockDao = mock(InvStockDao.class);
        when(stockDao.selectList(any())).thenReturn(List.of(
                stockRow("s-a", "B-A", "100", LocalDateTime.now().minusDays(5)),
                stockRow("s-b", "B-B", "200", LocalDateTime.now())));
        MaterialIssueServiceImpl svc = issueService(stockDao);

        Map<String, Object> payload = new HashMap<>();
        payload.put("issueType", "OWN");
        payload.put("workOrderNo", "WO-1");
        payload.put("lines", List.of(Map.of("itemCode", "RM1", "qty", 150)));

        @SuppressWarnings("unchecked")
        var plan = (List<com.erp.entity.vmi.MaterialIssueLine>) svc.preview(payload).get("lines");
        assertEquals(2, plan.size());
        assertEquals("B-A", plan.get(0).getBatchNo());
        assertEquals(0, new BigDecimal("100").compareTo(plan.get(0).getQty()), "最早批次先整批");
        assertEquals("B-B", plan.get(1).getBatchNo());
        assertEquals(0, new BigDecimal("50").compareTo(plan.get(1).getQty()));
        assertEquals(0, new BigDecimal("150").compareTo(
                plan.get(0).getQty().add(plan.get(1).getQty())));
    }

    /** material-issue MODIFIED 行为增强：同日入库按效期升序配批（BR-4.4-19 FEFO） */
    @Test
    void fifoPlanPrefersEarlierExpirySameDay() {
        InvStockDao stockDao = mock(InvStockDao.class);
        java.time.LocalDateTime sameDay = LocalDateTime.of(2026, 10, 1, 10, 0);
        when(stockDao.selectList(any())).thenReturn(List.of(
                stockRow("s-late", "B-LATE", "60", sameDay),
                stockRow("s-early", "B-EARLY", "60", sameDay)));
        // 效期台账：B-EARLY 更早到期
        com.erp.dao.inv.InvBatchDao batchDao = mock(com.erp.dao.inv.InvBatchDao.class);
        com.erp.entity.inv.InvBatch late = new com.erp.entity.inv.InvBatch();
        late.setBatchNo("B-LATE");
        late.setExpiryDate(java.time.LocalDate.of(2027, 6, 30));
        com.erp.entity.inv.InvBatch early = new com.erp.entity.inv.InvBatch();
        early.setBatchNo("B-EARLY");
        early.setExpiryDate(java.time.LocalDate.of(2026, 12, 31));
        when(batchDao.selectList(any())).thenReturn(List.of(late, early));

        com.erp.dao.inv.InvDocTypeDao docTypeDao = mock(com.erp.dao.inv.InvDocTypeDao.class);
        com.erp.service.inv.StockPostingEngine engine =
                new com.erp.service.impl.inv.StockPostingEngineImpl(
                        docTypeDao, stockDao, batchDao,
                        mock(com.erp.dao.inv.InvTransactionDao.class),
                        mock(com.erp.dao.inv.InvSerialDao.class),
                        mock(com.erp.dao.sd.ReservationDao.class),
                        mock(com.erp.dao.mdm.MdmItemDao.class),
                        mock(com.erp.ops.OutboxPublisher.class),
                        sysParamMock());
        MaterialIssueServiceImpl svc = new MaterialIssueServiceImpl(
                mock(MaterialIssueDao.class), mock(MaterialIssueLineDao.class), stockDao,
                mock(VmiStockDao.class), mock(MdmSupplierDao.class),
                mock(VmiAgreementService.class), mock(VmiAlertService.class),
                mock(AccrualService.class), engine,
                mock(com.erp.service.inv.FifoStrategyService.class), mock(com.erp.service.inv.PickTaskGate.class), mock(com.erp.service.inv.PickTaskService.class));

        Map<String, Object> payload = new HashMap<>();
        payload.put("issueType", "OWN");
        payload.put("workOrderNo", "WO-FEFO");
        payload.put("lines", List.of(Map.of("itemCode", "RM1", "qty", 100)));

        Map<String, Object> out = svc.preview(payload);
        @SuppressWarnings("unchecked")
        var lines = (List<com.erp.entity.vmi.MaterialIssueLine>) out.get("lines");
        // 同日：先配更早到期 B-EARLY 60，再配 B-LATE 40
        assertEquals("B-EARLY", lines.get(0).getBatchNo());
        assertEquals(0, new BigDecimal("60").compareTo(lines.get(0).getQty()));
        assertEquals("B-LATE", lines.get(1).getBatchNo());
        assertEquals(0, new BigDecimal("40").compareTo(lines.get(1).getQty()));
    }

    @Test
    void fifoPlanRejectsInsufficientStock() {
        InvStockDao stockDao = mock(InvStockDao.class);
        when(stockDao.selectList(any())).thenReturn(List.of(
                stockRow("s-a", "B-A", "80", LocalDateTime.now())));
        MaterialIssueServiceImpl svc = issueService(stockDao);

        Map<String, Object> payload = new HashMap<>();
        payload.put("issueType", "OWN");
        payload.put("workOrderNo", "WO-2");
        payload.put("lines", List.of(Map.of("itemCode", "RM1", "qty", 100)));

        ServiceException e = assertThrows(ServiceException.class, () -> svc.preview(payload));
        assertTrue(e.getMessage().contains("不足"), e.getMessage());
        assertTrue(e.getMessage().contains("缺口"), e.getMessage());
    }

    /** 引擎④效期锁定实时判定需 EXPIRY_LOCK_RATIO（mock 固定 0.5，与默认一致） */
    private static com.erp.service.SysParamService sysParamMock() {
        com.erp.service.SysParamService p = mock(com.erp.service.SysParamService.class);
        when(p.getRate(anyString(), any(java.math.BigDecimal.class)))
                .thenReturn(new java.math.BigDecimal("0.5"));
        return p;
    }
}

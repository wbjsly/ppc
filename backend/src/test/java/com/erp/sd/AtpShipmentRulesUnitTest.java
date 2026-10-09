package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.AtpFactsDao;
import com.erp.dao.sd.AtpTrialDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.SdReturnLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.SysParamService;
import com.erp.service.fin.InvoiceService;
import com.erp.service.impl.sd.AtpServiceImpl;
import com.erp.service.sd.ReservationService;
import com.erp.service.impl.sd.ShipmentServiceImpl;
import com.erp.service.system.NoticeService;
import com.erp.entity.mdm.MdmItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 17.3 规则单测：ATP 四因子与排除逻辑（QC/寄售/在制/延迟在途）、
 * 批次先到先得、锁不足阻断、拆行超量守恒（spec sales-atp-reservation /
 * sales-shipment，BR-4.3-20~24、FR-4.3-6-5）。
 */
class AtpShipmentRulesUnitTest {

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_SALES"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ================= ATP 四因子与排除 =================

    private AtpServiceImpl atp;
    private AtpFactsDao factsDao;
    private ReservationService reservationService;

    private void initAtp() {
        factsDao = mock(AtpFactsDao.class);
        reservationService = mock(ReservationService.class);
        MdmItemDaoHolder.item = new MdmItem();
        MdmItemDaoHolder.item.setItemCode("RM1");
        MdmItemDaoHolder.item.setItemName("不锈钢管");
        MdmItemDaoHolder.item.setSafetyStock(new BigDecimal("100"));
        SysParamService sp = mock(SysParamService.class);
        when(sp.getInt(eq("SAFETY_DAYS"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(30);
        atp = new AtpServiceImpl(factsDao, mock(AtpTrialDao.class),
                MdmItemDaoHolder.dao = mock(com.erp.dao.mdm.MdmItemDao.class),
                mock(SoDao.class), mock(SoLineDao.class), mock(SoChangeDao.class),
                reservationService, sp);
        when(MdmItemDaoHolder.dao.selectOne(any())).thenReturn(MdmItemDaoHolder.item);
        Map<String, Object> stock = new HashMap<>();
        stock.put("A", new BigDecimal("290"));   // 可用（已排除待检）
        stock.put("Q", new BigDecimal("10"));    // 待检锁定单列
        when(factsDao.stockFact("RM1", "WH-MAIN")).thenReturn(stock);
        when(factsDao.vmiRemain("RM1")).thenReturn(new BigDecimal("580")); // 寄售余量（排除）
        when(factsDao.issuedLast90("RM1")).thenReturn(new BigDecimal("3200"));
        when(reservationService.sumActive("RM1", "WH-MAIN")).thenReturn(new BigDecimal("50"));
    }

    /** 交期内在途 80 + 到货日缺失 50（不确定补货不纳入） */
    private void stubIncoming() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> ok = new HashMap<>();
        ok.put("arrive", LocalDate.now().plusDays(3).toString());
        ok.put("openQty", new BigDecimal("80"));
        rows.add(ok);
        Map<String, Object> noDate = new HashMap<>();
        noDate.put("arrive", null);
        noDate.put("openQty", new BigDecimal("50"));
        rows.add(noDate);
        when(factsDao.incomingRows("RM1")).thenReturn(rows);
    }

    @Test
    void atpFourFactorsExcludeQcAndVmi() {
        initAtp();
        stubIncoming();
        Map<String, Object> out = atp.trial("RM1", "WH-MAIN", new BigDecimal("200"),
                LocalDate.now().plusDays(10));
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) out.get("factors");
        // OnHand 已排除待检；QC 与寄售单列示不计入
        assertEquals(0, new BigDecimal("290").compareTo((BigDecimal) f.get("onHand")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) f.get("excludedQc")));
        assertEquals(0, new BigDecimal("580").compareTo((BigDecimal) f.get("excludedVmi")));
        // InProcess 恒 0（生产域未接入标注）
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) f.get("inProcess")));
        // Incoming 只算交期内 80；到货日缺失 50 计入延迟
        assertEquals(0, new BigDecimal("80").compareTo((BigDecimal) f.get("incoming")));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) f.get("incomingDelayed")));
        // SafetyStock = max(字段 100, 日均 3200/90 × 30 = 1066.668)
        BigDecimal safety = (BigDecimal) f.get("safetyStock");
        assertEquals(0, safety.compareTo(new BigDecimal("1066.668")));
        // available = 290(onHand) + 80(incoming) - 50(reserved) - 1066.668(safety) = -746.668
        BigDecimal available = (BigDecimal) out.get("available");
        assertEquals(0, available.compareTo(new BigDecimal("-746.668")));
        assertEquals(Boolean.FALSE, out.get("enough"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("分批交付")),
                String.valueOf(warnings)); // BR-4.3-22 不削量，引导分批
        assertTrue(warnings.stream().anyMatch(w -> w.contains("不计入 ATP")),
                String.valueOf(warnings)); // BR-4.3-20 不确定补货不纳入
    }

    @Test
    void atpEnoughWithSufficientOnHand() {
        initAtp();
        when(factsDao.incomingRows("RM1")).thenReturn(new ArrayList<>());
        when(factsDao.issuedLast90("RM1")).thenReturn(BigDecimal.ZERO);
        when(reservationService.sumActive("RM1", "WH-MAIN")).thenReturn(BigDecimal.ZERO);
        // available = 290 + 0 - 0 - max(100, 0) = 190
        Map<String, Object> out = atp.trial("RM1", "WH-MAIN", new BigDecimal("150"), null);
        assertEquals(Boolean.TRUE, out.get("enough"));
        assertEquals(0, new BigDecimal("190").compareTo((BigDecimal) out.get("available")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("shortage")));
    }

    /** mock 宿主（避免字段命名冲突） */
    static final class MdmItemDaoHolder {
        static com.erp.dao.mdm.MdmItemDao dao;
        static MdmItem item;
    }

    // ================= 批次 FIFO / 锁不足 / 拆行守恒 =================

    private ShipmentServiceImpl shipment;
    private ShipmentDao shipDao;
    private ShipmentLineDao shipLineDao;
    private InvStockDao stockDao;
    private SoDao soDao;
    private SoLineDao soLineDao;
    private ReservationDao reservationDao;

    private void initShipment() {
        shipDao = mock(ShipmentDao.class);
        shipLineDao = mock(ShipmentLineDao.class);
        stockDao = mock(InvStockDao.class);
        soDao = mock(SoDao.class);
        soLineDao = mock(SoLineDao.class);
        reservationDao = mock(ReservationDao.class);

        // 真实引擎注入（共享 stockDao/reservationDao）：双列扣减与 FIFO 断言穿引擎继续生效
        com.erp.dao.inv.InvDocTypeDao docTypeDao = mock(com.erp.dao.inv.InvDocTypeDao.class);
        com.erp.entity.inv.InvDocType salesOut = new com.erp.entity.inv.InvDocType();
        salesOut.setTypeCode("SALES_OUT");
        salesOut.setDirection("OUT");
        salesOut.setTypeName("销售出库");
        salesOut.setEnabled(1);
        salesOut.setNeedBatch(1);
        salesOut.setNeedSerial(0);
        when(docTypeDao.selectByCode("SALES_OUT")).thenReturn(salesOut);
        com.erp.service.inv.StockPostingEngine realEngine =
                new com.erp.service.impl.inv.StockPostingEngineImpl(
                        docTypeDao, stockDao, mock(com.erp.dao.inv.InvBatchDao.class),
                        mock(com.erp.dao.inv.InvTransactionDao.class),
                        mock(com.erp.dao.inv.InvSerialDao.class), reservationDao,
                        mock(com.erp.dao.mdm.MdmItemDao.class), mock(OutboxPublisher.class),
                        sysParamForEngine());
        shipment = new ShipmentServiceImpl(shipDao, shipLineDao, soDao, soLineDao,
                mock(SdReturnDao.class), mock(SdReturnLineDao.class), stockDao, reservationDao,
                mock(OutboxPublisher.class), mock(NoticeService.class),
                mock(SysParamService.class), mock(InvoiceService.class), realEngine, mock(com.erp.service.inv.PickTaskGate.class), mock(com.erp.service.inv.PickTaskService.class), mock(com.erp.dao.mdm.MdmCustomerGroupDao.class), mock(com.erp.dao.inv.InvWaveLineDao.class), mock(com.erp.dao.inv.InvWaveDao.class) );
    }

    private InvStock stock(String id, String batch, String qty) {
        InvStock s = new InvStock();
        s.setId(id);
        s.setWarehouseCode("WH-MAIN");
        s.setItemCode("RM1");
        s.setBatchNo(batch);
        s.setAvailableQty(new BigDecimal(qty));
        return s;
    }

    private ShipmentLine shipLine(BigDecimal qty) {
        ShipmentLine l = new ShipmentLine();
        l.setId("SL1");
        l.setShipId("SH1");
        l.setLineNo(1);
        l.setItemCode("RM1");
        l.setWarehouseCode("WH-MAIN");
        l.setQty(qty);
        l.setLineStatus("DRAFT");
        return l;
    }

    private Shipment draftShip() {
        Shipment s = new Shipment();
        s.setId("SH1");
        s.setShipNo("SH-T1");
        s.setStatus(Shipment.ST_DRAFT);
        s.setShipType("NORMAL");
        s.setWarehouseCode("WH-MAIN");
        s.setCustomerId("C1");
        return s;
    }

    private void stubReservationsFor(String... batches) {
        List<Reservation> list = new ArrayList<>();
        for (String b : batches) {
            Reservation r = new Reservation();
            r.setId("R-" + b);
            r.setStatus(Reservation.ST_ACTIVE);
            r.setBatchNo(b);
            r.setQty(new BigDecimal("100"));
            r.setLineId("SL1");
            list.add(r);
        }
        when(reservationDao.selectList(any())).thenReturn(list);
        when(reservationDao.sumActiveByLine(anyString())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void postConsumesOldestBatchFirst() {
        initShipment();
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        // SQL orderByAsc(createDate) → mock 模拟老批在前：old 60 / new 60，需求 100
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-OLD", "60"), stock("S2", "B-NEW", "60"))));
        when(stockDao.update(any(), any())).thenReturn(1);
        stubReservationsFor("B-OLD", "B-NEW");
        when(soLineDao.selectById(any())).thenReturn(null);
        when(shipLineDao.updateById(any())).thenReturn(1);
        when(shipDao.updateById(any())).thenReturn(1);

        Shipment done = shipment.post("SH1");
        assertEquals(Shipment.ST_POSTED, done.getStatus());
        // 批次先到先得：老批吃满 60，新批补 40
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stockDao, org.mockito.Mockito.atLeastOnce())
                .update(any(), any());
        // BATCH_ALLOC 落库顺序按消耗顺序
        org.mockito.ArgumentCaptor<ShipmentLine> cap =
                org.mockito.ArgumentCaptor.forClass(ShipmentLine.class);
        org.mockito.Mockito.verify(shipLineDao).updateById(cap.capture());
        String alloc = cap.getValue().getBatchAlloc();
        assertTrue(alloc.indexOf("B-OLD") < alloc.indexOf("B-NEW"), alloc);
        assertTrue(alloc.contains("60"), alloc);
        assertTrue(alloc.contains("40"), alloc);
    }

    @Test
    void postBlockedWhenStockShort() {
        initShipment();
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        // 总可用 50 < 需求 100 → FR-4.3-6-5 库存不足阻断
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-ONLY", "50"))));
        when(stockDao.update(any(), any())).thenReturn(1);

        ServiceException e = assertThrows(ServiceException.class, () -> shipment.post("SH1"));
        assertTrue(e.getMessage().contains("库存不足"), e.getMessage());
    }

    @Test
    void postBlockedOnConcurrentShortage() {
        initShipment();
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-OLD", "100"))));
        // 原子扣减影响行数 0 → 并发冲突（引擎 409 文案）
        when(stockDao.update(any(), any())).thenReturn(0);

        ServiceException e = assertThrows(ServiceException.class, () -> shipment.post("SH1"));
        assertTrue(e.getMessage().contains("库存并发冲突"), e.getMessage());
    }

    @Test
    void partialRejectsOverShipableQty() {
        initShipment();
        So so = new So();
        so.setId("SO1");
        so.setSoNo("SO-T1");
        so.setStatus(So.ST_CONFIRMED);
        so.setCustomerId("C1");
        when(soDao.selectById("SO1")).thenReturn(so);
        SoLine line = new SoLine();
        line.setId("L1");
        line.setSoId("SO1");
        line.setLineNo(1);
        line.setItemCode("RM1");
        line.setQty(new BigDecimal("100"));
        line.setShippedQty(BigDecimal.ZERO);
        line.setLineStatus("CONFIRMED");
        when(soLineDao.selectList(any())).thenReturn(List.of(line));
        when(soLineDao.selectById("L1")).thenReturn(line);
        when(shipLineDao.selectInFlightBySoLine("L1")).thenReturn(BigDecimal.ZERO);
        when(reservationDao.sumActiveByLine("L1")).thenReturn(new BigDecimal("100"));

        // 可发 = min(100, 100) - 0 = 100 → 300 超余量 422（拆行守恒：任一次 ≤ 剩余可发）
        ServiceException e = assertThrows(ServiceException.class, () -> shipment.generatePartial(
                "SO1", List.of(Map.of("soLineId", "L1", "qty", new BigDecimal("300")))));
        assertTrue(e.getMessage().contains("超出可发"), e.getMessage());
        // 合规量（≤ 剩余可发）放行的正向路径由端到端冒烟覆盖——
        // 单测直调成功路径需 MP TableInfo 元数据（TableInfoHelper 初始化），此处不做
    }

    /** 引擎④效期锁定实时判定需 EXPIRY_LOCK_RATIO（mock 固定 0.5，与默认一致） */
    private static com.erp.service.SysParamService sysParamForEngine() {
        com.erp.service.SysParamService p = org.mockito.Mockito.mock(com.erp.service.SysParamService.class);
        org.mockito.Mockito.when(p.getRate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.math.BigDecimal.class)))
                .thenReturn(new java.math.BigDecimal("0.5"));
        return p;
    }
}

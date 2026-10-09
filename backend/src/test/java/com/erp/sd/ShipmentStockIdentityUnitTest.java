package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.SdReturnLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.SysParamService;
import com.erp.service.fin.InvoiceService;
import com.erp.service.impl.sd.ShipmentServiceImpl;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出库过账双列原子扣减与行级恒等（spec sales-shipment MODIFIED，
 * FR-4.3-6-5：AVAILABLE_QTY 与 QTY 同 update 同减，
 * 保持 QTY = AVAILABLE + QC (+ FIN)，stock-snapshot 校验地基）。
 */
class ShipmentStockIdentityUnitTest {

    private ShipmentServiceImpl shipment;
    private ShipmentDao shipDao;
    private ShipmentLineDao shipLineDao;
    private SoLineDao soLineDao;
    private InvStockDao stockDao;
    private ReservationDao reservationDao;

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        shipDao = mock(ShipmentDao.class);
        shipLineDao = mock(ShipmentLineDao.class);
        soLineDao = mock(SoLineDao.class);
        stockDao = mock(InvStockDao.class);
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
        shipment = new ShipmentServiceImpl(shipDao, shipLineDao, mock(SoDao.class), soLineDao,
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
        s.setQty(new BigDecimal(qty));   // 恒等前提：无冻结时 QTY = AVAILABLE
        return s;
    }

    private ShipmentLine shipLine(BigDecimal qty) {
        ShipmentLine l = new ShipmentLine();
        l.setId("SL1");
        l.setShipId("SH1");
        l.setLineNo(1);
        l.setItemCode("RM1");
        l.setItemName("不锈钢管");
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
        when(reservationDao.sumActiveByLine(anyString())).thenReturn(new BigDecimal("100"));
    }

    /** 从 SET 子句里抠出所有「列 = 列 - 数值」的扣减量 */
    private List<String> deductValues(String sqlSet, String column) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(Pattern.quote(column) + " = " + Pattern.quote(column)
                + " - ([0-9.]+)").matcher(sqlSet);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 2.1 过账后两列同减：同一 update 内 AVAILABLE_QTY 与 QTY 扣减量必须相等 */
    @Test
    void postDeductsBothColumnsAtomically() {
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-OLD", "60"), stock("S2", "B-NEW", "60"))));
        when(stockDao.update(any(), any())).thenReturn(1);
        stubReservationsFor("B-OLD", "B-NEW");
        when(soLineDao.selectById(any())).thenReturn(null);
        when(shipLineDao.updateById(any())).thenReturn(1);
        when(shipDao.updateById(any())).thenReturn(1);

        shipment.post("SH1");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvStock>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(stockDao, org.mockito.Mockito.atLeastOnce()).update(any(), cap.capture());

        int updates = 0;
        for (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvStock> w
                : cap.getAllValues()) {
            String sqlSet = w.getSqlSet();
            assertTrue(sqlSet.contains("AVAILABLE_QTY = AVAILABLE_QTY - "), sqlSet);
            assertTrue(sqlSet.contains("QTY = QTY - "), sqlSet);
            List<String> av = deductValues(sqlSet, "AVAILABLE_QTY");
            List<String> qt = deductValues(sqlSet, "QTY");
            assertEquals(av, qt, "两列扣减量必须逐一相等：" + sqlSet);
            updates++;
        }
        assertTrue(updates >= 2, "FIFO 两批各一次扣减，实际 " + updates);
    }

    /** 2.2 多批次 FIFO：老批吃满 60、新批补 40，各自双列等量 */
    @Test
    void fifoDeductAmountsFollowAllocation() {
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-OLD", "60"), stock("S2", "B-NEW", "60"))));
        when(stockDao.update(any(), any())).thenReturn(1);
        stubReservationsFor("B-OLD", "B-NEW");
        when(soLineDao.selectById(any())).thenReturn(null);
        when(shipLineDao.updateById(any())).thenReturn(1);
        when(shipDao.updateById(any())).thenReturn(1);

        shipment.post("SH1");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvStock>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(stockDao, org.mockito.Mockito.atLeastOnce()).update(any(), cap.capture());

        List<String> all = new ArrayList<>();
        for (var w : cap.getAllValues()) {
            all.addAll(deductValues(w.getSqlSet(), "AVAILABLE_QTY"));
        }
        assertEquals(List.of("60", "40"), all, "老批 60 → 新批 40，实际 " + all);
    }

    /** 2.2 库存不足仍 422 阻断（可发 30 / 需求 100） */
    @Test
    void insufficientStockBlocked() {
        when(shipDao.selectById("SH1")).thenReturn(draftShip());
        when(shipLineDao.selectList(any())).thenReturn(List.of(shipLine(new BigDecimal("100"))));
        when(stockDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                stock("S1", "B-ONLY", "30"))));
        when(stockDao.update(any(), any())).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> shipment.post("SH1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("库存不足"), ex.getMessage());
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

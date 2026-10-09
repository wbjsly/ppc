package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.erp.entity.inv.InvStock;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.fin.AccrualService;
import com.erp.service.impl.proc.GoodsReceiptServiceImpl;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.proc.AsnService;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 收货过账库存 upsert 的引擎委托断言（add-stock-posting-engine 任务 4.1，
 * receipt-posting MODIFIED ④：库存变更经 PURCHASE_IN 引擎通道）。
 * INBOUND_DATE 首插/补货语义已由引擎承载，DB 断言见 StockPostingEngineDbTest。
 */
class ReceiptInboundDateUnitTest {

    private InvStockDao stockDao;
    private StockPostingEngine engine;
    private GoodsReceiptServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        stockDao = mock(InvStockDao.class);
        engine = mock(StockPostingEngine.class);
        com.erp.dao.inv.InvPutawayDao putawayDao = mock(com.erp.dao.inv.InvPutawayDao.class);
        // upsertStock 内 confirmedBin：返回 CONFIRMED 分配位（强前置产物）
        com.erp.entity.inv.InvPutaway conf = new com.erp.entity.inv.InvPutaway();
        conf.setBinCode("T-BIN-1");
        conf.setStatus("CONFIRMED");
        org.mockito.Mockito.when(putawayDao.selectOne(any())).thenReturn(conf);
        // 无 Spring 环境下初始化 MP 表元数据（LambdaUpdateWrapper.set 需要 lambda cache）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                InvStock.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.erp.entity.inv.InvPutaway.class);
        // @RequiredArgsConstructor 全参构造：22 个依赖按声明序 mock（引擎为追加的末位字段）
        service = new GoodsReceiptServiceImpl(
                mock(GoodsReceiptDao.class),
                mock(GoodsReceiptLineDao.class),
                mock(ReceiptDifferenceDao.class),
                mock(ReceiptAdjustmentDao.class),
                stockDao,
                mock(PurchaseOrderDao.class),
                mock(PurchaseOrderLineDao.class),
                mock(PurchaseOrderVersionDao.class),
                mock(MdmSupplierDao.class),
                mock(MdmItemDao.class),
                mock(OutboxPublisher.class),
                mock(InspectionLotService.class),
                mock(ConcessionDao.class),
                mock(AccrualService.class),
                mock(AsnService.class),
                mock(AsnDao.class),
                mock(VmiStockDao.class),
                mock(VmiAgreementDao.class),
                mock(VmiAgreementService.class),
                mock(VmiAlertService.class),
                mock(ObjectMapper.class),
                engine,
                putawayDao);
        ReflectionTestUtils.setField(service, "receiptTolerance", new BigDecimal("0.005"));
        ReflectionTestUtils.setField(service, "qcHoursA", 24);
        ReflectionTestUtils.setField(service, "qcHoursB", 48);
        ReflectionTestUtils.setField(service, "qcHoursC", 72);
    }

    private GoodsReceiptLine line(String qcStatus) {
        GoodsReceiptLine gl = new GoodsReceiptLine();
        gl.setId("grl-1");
        gl.setItemCode("IT-001");
        gl.setItemName("测试物料");
        gl.setQcStatus(qcStatus);
        return gl;
    }

    private GoodsReceipt gr() {
        GoodsReceipt g = new GoodsReceipt();
        g.setGrNo("GR-TEST-1");
        g.setBatchNo("B261008-0001");
        return g;
    }

    private StockPostingEngine.Request capturedRequest() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StockPostingEngine.Request> cap =
                ArgumentCaptor.forClass(StockPostingEngine.Request.class);
        verify(engine).post(cap.capture());
        return cap.getValue();
    }

    /** 合格放行走引擎：PURCHASE_IN + intoQc=false + 双列/流水/建档由引擎承载 */
    @Test
    void releasedLineDelegatesToEngine() {
        ReflectionTestUtils.invokeMethod(service, "upsertStock",
                gr(), line("RELEASED"), new BigDecimal("60"));

        StockPostingEngine.Request req = capturedRequest();
        assertEquals("PURCHASE_IN", req.typeCode);
        assertEquals("GR", req.bizDocType);
        assertEquals("GR-TEST-1", req.bizDocNo);
        assertEquals(1, req.lines.size());
        StockPostingEngine.Line l = req.lines.get(0);
        assertEquals(InvStock.DEFAULT_WH, l.warehouseCode);
        assertEquals("IT-001", l.itemCode);
        assertEquals("B261008-0001", l.batchNo);
        assertEquals(0, new BigDecimal("60").compareTo(l.qty));
        assertTrue(!l.intoQc, "合格量入 AVAILABLE，intoQc=false");
        // 数量列不再由 GR 直写
        verify(stockDao, never()).insert(any(InvStock.class));
    }

    /** 让步量入 QC（intoQc=true）+ 让步限制快照留域 */
    @Test
    void concessionLineDelegatesAndWritesLimitSnapshot() {
        org.mockito.Mockito.when(stockDao.update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "upsertStock",
                gr(), line("CONCESSION"), new BigDecimal("30"));

        StockPostingEngine.Request req = capturedRequest();
        assertEquals("PURCHASE_IN", req.typeCode);
        assertTrue(req.lines.get(0).intoQc, "让步量入 QC_QTY，intoQc=true");
        // 限制快照（域副作用）：stockDao update 携带 CONCESSION_FLAG/LIMIT
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<InvStock>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(stockDao).update(org.mockito.ArgumentMatchers.isNull(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertTrue(sqlSet == null || !sqlSet.toUpperCase().contains("INBOUND_DATE"),
                "让步快照更新不得触碰数量列/入库日期");
    }
}

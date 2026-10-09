package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvDocTypeDao;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.InvTransactionDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvDocType;
import com.erp.ops.OutboxPublisher;
import com.erp.service.impl.inv.StockPostingEngineImpl;
import com.erp.service.inv.StockPostingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引擎入口错误码语义单测（spec stock-doc-type / stock-posting-engine，任务 3.1/3.6）：
 * 类型不存在 422、停用 422、空行 422、量非正 422；错误路径不触碰库存表。
 */
class StockPostingEngineUnitTest {

    private StockPostingEngineImpl engine;
    private InvDocTypeDao docTypeDao;
    private InvStockDao stockDao;

    @BeforeEach
    void setUp() {
        docTypeDao = mock(InvDocTypeDao.class);
        stockDao = mock(InvStockDao.class);
        engine = new StockPostingEngineImpl(docTypeDao, stockDao,
                mock(InvBatchDao.class), mock(InvTransactionDao.class),
                mock(InvSerialDao.class), mock(ReservationDao.class),
                mock(MdmItemDao.class), mock(OutboxPublisher.class));
    }

    private InvDocType type(String code, int enabled) {
        InvDocType t = new InvDocType();
        t.setTypeCode(code);
        t.setDirection("IN");
        t.setTypeName("测试类型");
        t.setEnabled(enabled);
        t.setNeedBatch(1);
        t.setNeedSerial(0);
        return t;
    }

    private StockPostingEngine.Request req(String typeCode, String qty) {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = "WH-UNIT";
        l.itemCode = "IT-UNIT";
        l.batchNo = "B1";
        l.qty = qty == null ? null : new BigDecimal(qty);
        StockPostingEngine.Request r = new StockPostingEngine.Request();
        r.typeCode = typeCode;
        r.bizDocType = "GR";
        r.bizDocNo = "UNIT-1";
        r.lines = List.of(l);
        return r;
    }

    @Test
    void unknownTypeRejected() {
        when(docTypeDao.selectByCode("NOPE")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.post(req("NOPE", "10")));
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
        verify(stockDao, never()).selectOne(any());
    }

    @Test
    void disabledTypeRejected() {
        when(docTypeDao.selectByCode("SLOW_TYPE")).thenReturn(type("SLOW_TYPE", 0));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.post(req("SLOW_TYPE", "10")));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
        verify(stockDao, never()).selectOne(any());
    }

    @Test
    void emptyLinesRejected() {
        StockPostingEngine.Request r = req("PURCHASE_IN", "10");
        r.lines = List.of();
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(r));
        assertTrue(ex.getMessage().contains("不能为空"), ex.getMessage());
    }

    @Test
    void nonPositiveQtyRejectedBeforeStockTouch() {
        when(docTypeDao.selectByCode("PURCHASE_IN")).thenReturn(type("PURCHASE_IN", 1));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.post(req("PURCHASE_IN", "0")));
        assertTrue(ex.getMessage().contains("大于 0"), ex.getMessage());
        verify(stockDao, never()).selectOne(any());
        verify(stockDao, never()).insert(any(com.erp.entity.inv.InvStock.class));
    }
}

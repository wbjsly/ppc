package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.service.SysParamService;
import com.erp.service.impl.inv.BatchRecommendServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次推荐试算单测（spec outbound-strategy 批次推荐试算，任务 2.1/2.3）：
 * FIFO 分批取量、同日效期升序、效期锁定剔除（含实时计算兜底）、缺口与分批建议、入参 422。
 */
class BatchRecommendUnitTest {

    private BatchRecommendServiceImpl service;
    private InvStockDao stockDao;
    private InvBatchDao batchDao;
    private ReservationDao reservationDao;
    private SysParamService sysParamService;

    @BeforeEach
    void setUp() {
        stockDao = mock(InvStockDao.class);
        batchDao = mock(InvBatchDao.class);
        reservationDao = mock(ReservationDao.class);
        sysParamService = mock(SysParamService.class);
        service = new BatchRecommendServiceImpl(stockDao, batchDao, reservationDao, sysParamService,
                mock(com.erp.dao.inv.ExpiryLockLogDao.class));
        when(sysParamService.getRate(anyString(), org.mockito.ArgumentMatchers.any(BigDecimal.class)))
                .thenReturn(new BigDecimal("0.5"));
        when(sysParamService.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(30);
        when(batchDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(reservationDao.sumActiveOnBatch(anyString(), anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);
    }

    private InvStock row(String batch, String bin, String avail, String inbound) {
        InvStock s = new InvStock();
        s.setWarehouseCode("WH-MAIN");
        s.setItemCode("A");
        s.setBatchNo(batch);
        s.setBinCode(bin);
        s.setAvailableQty(new BigDecimal(avail));
        s.setQty(new BigDecimal(avail));
        s.setInboundDate(LocalDate.parse(inbound));
        s.setCreateDate(LocalDateTime.of(2026, 1, 1, 0, 0));
        return s;
    }

    private InvBatch ledger(String batch, String production, String expiry, String lock) {
        InvBatch b = new InvBatch();
        b.setBatchNo(batch);
        b.setItemCode("A");
        b.setProductionDate(production == null ? null : LocalDate.parse(production));
        b.setExpiryDate(expiry == null ? null : LocalDate.parse(expiry));
        b.setExpiryLockFlag(lock);
        return b;
    }

    /** FIFO 分批：先出最早入库批次，末位缺口取量（BR-4.4-19） */
    @Test
    void fifoSplitsByOldestBatch() {
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B2", "", "80", "2026-01-15"),
                row("B1", "", "50", "2026-01-01"),
                row("B3", "", "200", "2026-01-06")));
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("100"), false);

        assertTrue((Boolean) r.get("satisfied"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.get("lines");
        assertEquals("B1", lines.get(0).get("batchNo"));
        assertEquals(new BigDecimal("50"), lines.get(0).get("take"));
        assertEquals("B3", lines.get(1).get("batchNo"));
        assertEquals(new BigDecimal("50"), lines.get(1).get("take"));
        assertEquals(2, lines.size());
    }

    /** 同日入库按效期升序（FEFO 决胜，BR-4.4-19） */
    @Test
    void sameInboundDatePrefersEarlierExpiry() {
        when(batchDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ledger("B4", "2026-09-01", "2028-12-31", "0"),
                ledger("B5", "2026-09-01", "2027-06-30", "0")));
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B4", "", "100", "2026-01-06"),
                row("B5", "", "100", "2026-01-06")));
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("60"), false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.get("lines");
        assertEquals("B5", lines.get(0).get("batchNo"));
    }

    /** 效期锁定批次剔出推荐池并列入 excluded（BR-4.4-20） */
    @Test
    void lockedBatchExcluded() {
        when(batchDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ledger("B0", "2026-01-01", "2026-12-31", "1"),
                ledger("B1", "2026-01-01", "2027-12-31", "0")));
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B0", "", "100", "2026-01-01"),
                row("B1", "", "100", "2026-01-02")));
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("50"), false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.get("lines");
        assertEquals("B1", lines.get(0).get("batchNo"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> excluded = (List<Map<String, Object>>) r.get("excluded");
        assertEquals("B0", excluded.get(0).get("batchNo"));
    }

    /** 实时计算兜底：标记位为 0 但当日已过锁定线的批次仍被剔除（任务 2.3） */
    @Test
    void realtimeLockCatchesUnflaggedBatch() {
        // 生产 2026-01-01、效期 2026-01-11：总 10 天，阈值 5 天；剩余已 ≤ 5 → 锁定
        when(batchDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ledger("BN", "2026-01-01", LocalDate.now().plusDays(3).toString(), "0"),
                ledger("BOK", "2026-01-01", "2027-12-31", "0")));
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("BN", "", "100", LocalDate.now().toString()),
                row("BOK", "", "100", LocalDate.now().toString())));
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("50"), false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.get("lines");
        assertEquals("BOK", lines.get(0).get("batchNo"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> excluded = (List<Map<String, Object>>) r.get("excluded");
        assertTrue(excluded.stream().anyMatch(e -> "BN".equals(e.get("batchNo"))));
    }

    /** 可用量不足：satisfied=false、gap 与分批建议非空（FR-4.4-3-2 异常处理） */
    @Test
    void insufficientGivesGapAndSplitSuggestion() {
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B1", "", "60", "2026-01-01")));
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("100"), false);

        assertFalse((Boolean) r.get("satisfied"));
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) r.get("gap")));
        assertTrue(String.valueOf(r.get("splitSuggestion")).contains("缺口"));
    }

    /** 入参非法 422（spec 场景：试算入参校验） */
    @Test
    void invalidInputRejected() {
        assertThrows(ServiceException.class,
                () -> service.recommend("WH-MAIN", "", BigDecimal.ONE, false));
        assertThrows(ServiceException.class,
                () -> service.recommend("", "A", BigDecimal.ONE, false));
        assertThrows(ServiceException.class,
                () -> service.recommend("WH-MAIN", "A", BigDecimal.ZERO, false));
        assertThrows(ServiceException.class,
                () -> service.recommend("WH-MAIN", "A", new BigDecimal("-1"), false));
    }

    /** 效期警告（BR-4.4-21）：剩余 < MIN_REMAINING_SHELF_DAYS → 标记但仍留在推荐池 */
    @Test
    void expiryWarningKeepsBatchInPool() {
        // 生产 2026-01-01、效期 400 天后：总 ~365+，剩余 35 天 > 锁定线（总×0.5）但 < 警告线 30？——
        // 取剩余 20 天 < 30（警告）且未过锁定线：总 400 天、阈值 200，剩余 20 < 200 → 会被锁。
        // 构造：总 3650 天（10 年保质期）、剩余 20 天 → 阈值 1825，20 < 1825 也锁……
        // 锁定公式（剩余 < 总×0.5）意味着长保质期物料后期必然锁定——正常；
        // 警告级（未锁定但 < 30 天）只可能出现在 总×0.5 ≤ 剩余 < 30 的窗口：
        // 总 ≤ 60 天的短保质期物料：总 50 天、剩余 25 天 → 阈值 25，25 < 25 否 → 不锁；25 < 30 → 警告
        when(batchDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ledger("B-SHORT", LocalDate.now().minusDays(25).toString(),
                        LocalDate.now().plusDays(25).toString(), "0"),
                ledger("B-OK2", "2026-01-01", "2027-12-31", "0")));
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B-SHORT", "", "100", LocalDate.now().toString()),
                row("B-OK2", "", "10", "2026-01-01")));   // 早入库但只 10 → 拆到 B-SHORT
        Map<String, Object> r = service.recommend("WH-MAIN", "A", new BigDecimal("30"), false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.get("lines");
        // 短保批次未过锁定线（剩余 25 ≥ 阈值 25）→ 留在推荐池且带警告
        assertTrue(lines.stream().anyMatch(l -> "B-SHORT".equals(l.get("batchNo"))),
                "警告批次仍在推荐池");
        Map<String, Object> shortLine = lines.stream()
                .filter(l -> "B-SHORT".equals(l.get("batchNo"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, shortLine.get("expiryWarning"));
    }

    /** 试算只读：不触碰任何写 DAO（mock 无 stub 的写方法被调会返回默认值，这里验证调用面） */
    @Test
    void recommendIsReadOnly() {
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B1", "", "50", "2026-01-01")));
        service.recommend("WH-MAIN", "A", new BigDecimal("10"), false);
        org.mockito.Mockito.verify(stockDao, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any(InvStock.class));
        org.mockito.Mockito.verify(stockDao, org.mockito.Mockito.never())
                .update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 波次共享预算（spec wave-management 波次级统一分配，design D3 防双扣）：
     * 波次内单据自身预留不占预算——同一库存下 recommend 与 recommendForWave 预算差 = 自身预留。
     */
    @Test
    void waveBudgetExcludesOwnReservation() {
        // 批次 B1：可用 100；总预留 80（其中波次内单据自身 60）
        when(stockDao.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                row("B1", "", "100", "2026-01-01")));
        when(reservationDao.sumActiveOnBatch("WH-MAIN", "A", "B1"))
                .thenReturn(new BigDecimal("80"));
        when(reservationDao.sumActiveOnBatchBySos("WH-MAIN", "A", List.of("so-wave-1")))
                .thenReturn(List.of(Map.of("batchNo", "B1", "qty", new BigDecimal("60"))));

        // 普通口径：预算 = 100 − 80 = 20 → 需求 50 缺口
        Map<String, Object> normal = service.recommend("WH-MAIN", "A", new BigDecimal("50"), false);
        assertEquals(Boolean.FALSE, normal.get("satisfied"), "普通口径自身预留占预算 → 缺口");

        // 波次口径：预算 = 100 − (80 − 60) = 80 → 需求 50 满足
        Map<String, Object> wave = service.recommendForWave("WH-MAIN", "A",
                new BigDecimal("50"), false, List.of("so-wave-1"));
        assertEquals(Boolean.TRUE, wave.get("satisfied"), "波次口径排除自身预留 → 满足");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) wave.get("lines");
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) lines.get(0).get("take")));

        // 排除集合为空 → 与普通口径同（对拍）
        Map<String, Object> empty = service.recommendForWave("WH-MAIN", "A",
                new BigDecimal("50"), false, List.of());
        assertEquals(normal.get("satisfied"), empty.get("satisfied"), "空排除集合与 recommend 同口径");
    }
}

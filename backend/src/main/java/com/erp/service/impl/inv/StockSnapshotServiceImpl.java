package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvCheckReportDao;
import com.erp.dao.inv.InvDailyBalanceDao;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvCheckReport;
import com.erp.entity.inv.InvDailyBalance;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.service.inv.StockSnapshotService;
import com.erp.service.system.NoticeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 库存三态快照查询实现（spec stock-snapshot）。
 * 四列口径（FR-4.4-2-2 / BR-4.4-14）：
 *   在手 = QTY；冻结 = QC_QTY + FIN_QTY；预留 = 该 SKU+仓+批次 ACTIVE 汇总；
 *   可用 = 在手 − 冻结 − 预留（实时计算，design D3 不读 AVAILABLE_QTY 列）。
 * VMI 寄售在 erp_inv_vmi_stock 独立表，本查询天然不含（BR-4.4-14 单独核算）。
 */
@Slf4j
@Service
public class StockSnapshotServiceImpl implements StockSnapshotService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InvStockDao stockDao;
    private final ReservationDao reservationDao;
    private final InvFreezeDao freezeDao;
    private final InvCheckReportDao checkReportDao;
    private final InvDailyBalanceDao dailyBalanceDao;
    private final NoticeService noticeService;

    public StockSnapshotServiceImpl(InvStockDao stockDao, ReservationDao reservationDao,
                                    InvFreezeDao freezeDao, InvCheckReportDao checkReportDao,
                                    InvDailyBalanceDao dailyBalanceDao, NoticeService noticeService) {
        this.stockDao = stockDao;
        this.reservationDao = reservationDao;
        this.freezeDao = freezeDao;
        this.checkReportDao = checkReportDao;
        this.dailyBalanceDao = dailyBalanceDao;
        this.noticeService = noticeService;
    }

    @Override
    public Map<String, Object> query(String warehouseCode, String itemCode,
                                     String batchNo, String keyword) {
        LambdaQueryWrapper<InvStock> qw = new LambdaQueryWrapper<InvStock>()
                .eq(!isBlank(warehouseCode), InvStock::getWarehouseCode, warehouseCode)
                .eq(!isBlank(itemCode), InvStock::getItemCode, itemCode)
                .eq(!isBlank(batchNo), InvStock::getBatchNo, batchNo)
                .and(!isBlank(keyword), w -> w.like(InvStock::getItemCode, keyword)
                        .or().like(InvStock::getItemName, keyword)
                        .or().like(InvStock::getBatchNo, keyword));
        List<InvStock> stocks = stockDao.selectList(qw
                .orderByAsc(InvStock::getItemCode).orderByAsc(InvStock::getBatchNo)
                .orderByAsc(InvStock::getBinCode));

        // 预留：一次取全部 ACTIVE 内存聚合（量小），避免 N+1
        Map<String, BigDecimal> reservedByKey = new LinkedHashMap<>();
        for (Reservation r : reservationDao.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE))) {
            String key = dimKey(r.getWarehouseCode(), r.getItemCode(), r.getBatchNo());
            reservedByKey.merge(key, nvl(r.getQty()), BigDecimal::add);
        }

        // 最近一次校验差异维度 → 「待核实」标记
        java.util.Set<String> pendingKeys = pendingVerifyKeys();

        // 位行明细 + 批次合计（spec stock-snapshot MODIFIED：位行 = SKU+仓+批+仓位，
        // 预留批次级只计一次，四列公式在批次合计/总计承载；行内 reserved/available
        // 为所属批次的批次级参考值，展示不重复累计）
        Map<String, Map<String, Object>> batchAgg = new LinkedHashMap<>();
        for (InvStock s : stocks) {
            String key = dimKey(s.getWarehouseCode(), s.getItemCode(), s.getBatchNo());
            Map<String, Object> agg = batchAgg.computeIfAbsent(key, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("warehouseCode", s.getWarehouseCode());
                a.put("itemCode", s.getItemCode());
                a.put("itemName", s.getItemName());
                a.put("batchNo", s.getBatchNo());
                a.put("qty", BigDecimal.ZERO);
                a.put("frozen", BigDecimal.ZERO);
                a.put("reserved", reservedByKey.getOrDefault(key, BigDecimal.ZERO));
                return a;
            });
            agg.put("qty", ((BigDecimal) agg.get("qty")).add(nvl(s.getQty())));
            agg.put("frozen", ((BigDecimal) agg.get("frozen"))
                    .add(nvl(s.getQcQty())).add(nvl(s.getFinQty())));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvStock s : stocks) {
            String key = dimKey(s.getWarehouseCode(), s.getItemCode(), s.getBatchNo());
            Map<String, Object> agg = batchAgg.get(key);
            BigDecimal reserved = (BigDecimal) agg.get("reserved");
            BigDecimal bQty = (BigDecimal) agg.get("qty");
            BigDecimal bFrozen = (BigDecimal) agg.get("frozen");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("warehouseCode", s.getWarehouseCode());
            row.put("itemCode", s.getItemCode());
            row.put("itemName", s.getItemName());
            row.put("batchNo", s.getBatchNo());
            row.put("binCode", str0(s.getBinCode()));
            row.put("qty", nvl(s.getQty()));
            row.put("qcQty", nvl(s.getQcQty()));
            row.put("finQty", nvl(s.getFinQty()));
            row.put("frozen", nvl(s.getQcQty()).add(nvl(s.getFinQty())));
            // 批次级参考值（同批次多仓位行显示同值，合计不按行累计）
            row.put("reserved", reserved);
            row.put("available", bQty.subtract(bFrozen).subtract(reserved));
            row.put("batchLevel", true);
            row.put("inboundDate", s.getInboundDate());
            row.put("concessionFlag", s.getConcessionFlag());
            row.put("pendingVerify", pendingKeys.contains(key));
            rows.add(row);
        }

        // 批次合计（承载完整四列公式：可用 = 在手 − 冻结 − 预留，预留只计一次）
        List<Map<String, Object>> batchTotals = new ArrayList<>();
        BigDecimal tQty = BigDecimal.ZERO;
        BigDecimal tFrozen = BigDecimal.ZERO;
        BigDecimal tReserved = BigDecimal.ZERO;
        BigDecimal tAvailable = BigDecimal.ZERO;
        for (Map<String, Object> agg : batchAgg.values()) {
            BigDecimal qty = (BigDecimal) agg.get("qty");
            BigDecimal frozen = (BigDecimal) agg.get("frozen");
            BigDecimal reserved = (BigDecimal) agg.get("reserved");
            Map<String, Object> bt = new LinkedHashMap<>(agg);
            bt.put("available", qty.subtract(frozen).subtract(reserved));
            batchTotals.add(bt);
            tQty = tQty.add(qty);
            tFrozen = tFrozen.add(frozen);
            tReserved = tReserved.add(reserved);
            tAvailable = tAvailable.add(qty.subtract(frozen).subtract(reserved));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("qty", tQty);
        totals.put("frozen", tFrozen);
        totals.put("reserved", tReserved);
        totals.put("available", tAvailable);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("batchTotals", batchTotals);
        out.put("totals", totals);
        out.put("asOf", LocalDateTime.now());
        return out;
    }

    @Override
    public List<Map<String, Object>> reservedDetail(String warehouseCode, String itemCode,
                                                    String batchNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Reservation> list = reservationDao.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE)
                .eq(!isBlank(warehouseCode), Reservation::getWarehouseCode, warehouseCode)
                .eq(!isBlank(itemCode), Reservation::getItemCode, itemCode)
                .eq(!isBlank(batchNo), Reservation::getBatchNo, batchNo)
                .orderByAsc(Reservation::getLockAt));
        for (Reservation r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("soNo", r.getSoNo());
            m.put("lineNo", r.getLineNo());
            m.put("lockAt", r.getLockAt());
            m.put("qty", nvl(r.getQty()));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> freezeDetail(String warehouseCode, String itemCode,
                                                  String batchNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<InvFreeze> list = freezeDao.selectList(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE)
                .eq(!isBlank(warehouseCode), InvFreeze::getWarehouseCode, warehouseCode)
                .eq(!isBlank(itemCode), InvFreeze::getItemCode, itemCode)
                .eq(!isBlank(batchNo), InvFreeze::getBatchNo, batchNo)
                .orderByDesc(InvFreeze::getCreateDate));
        for (InvFreeze f : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("freezeNo", f.getFreezeNo());
            m.put("freezeType", f.getFreezeType());
            m.put("qty", nvl(f.getQty()));
            m.put("reason", f.getReason());
            m.put("status", f.getStatus());
            m.put("source", f.getSource());
            m.put("ncrId", f.getNcrId());
            m.put("createDate", f.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> latestCheckReport() {
        InvCheckReport r = checkReportDao.selectLatest();
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) {
            out.put("exists", false);
            return out;
        }
        out.put("exists", true);
        out.put("runDate", r.getRunDate());
        out.put("status", r.getStatus());
        out.put("mismatchCnt", r.getMismatchCnt());
        out.put("detailJson", r.getDetailJson());
        return out;
    }

    @Override
    public Map<String, Object> wipQuery() {
        // 工单域（5.4）未建 → 恒空（偏差 D5）；契约就位，5.x 落地仅换数据源
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", List.of());
        out.put("asOf", LocalDateTime.now());
        return out;
    }

    // ================= 调度核心（design D6：逻辑在服务层，调度器薄壳，可脱离 cron 测试） =================

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> runCheckReport() {
        LocalDate runDate = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runDate", runDate);
        if (checkReportDao.countByRunDate(runDate) > 0) {
            InvCheckReport existing = checkReportDao.selectLatest();
            out.put("skipped", true);
            out.put("status", existing == null ? null : existing.getStatus());
            out.put("mismatchCnt", existing == null ? 0 : existing.getMismatchCnt());
            return out;
        }

        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>())) {
            BigDecimal expect = nvl(s.getAvailableQty());
            // 行级恒等（预留代数抵消、在制恒 0 → QTY = AVAILABLE + QC + FIN）
            boolean balanced = nvl(s.getQty()).compareTo(
                    nvl(s.getQcQty()).add(nvl(s.getFinQty())).add(expect)) == 0;
            if (!balanced) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("warehouseCode", s.getWarehouseCode());
                m.put("itemCode", s.getItemCode());
                m.put("batchNo", s.getBatchNo());
                m.put("binCode", str0(s.getBinCode()));
                m.put("qty", nvl(s.getQty()));
                m.put("availableQty", expect);
                m.put("qcQty", nvl(s.getQcQty()));
                m.put("finQty", nvl(s.getFinQty()));
                m.put("expect", nvl(s.getQcQty()).add(nvl(s.getFinQty())).add(expect));
                mismatches.add(m);
            }
        }

        InvCheckReport report = new InvCheckReport();
        report.setRunDate(runDate);
        report.setMismatchCnt(mismatches.size());
        report.setStatus(mismatches.isEmpty() ? InvCheckReport.ST_OK : InvCheckReport.ST_MISMATCH);
        try {
            report.setDetailJson(MAPPER.writeValueAsString(mismatches));
        } catch (Exception e) {
            report.setDetailJson("[]");
        }
        checkReportDao.insert(report);

        if (!mismatches.isEmpty()) {
            // 通知仓库主管（BR-4.4-15；仅落表 → 偏差 D1）
            noticeService.push("ROLE_WAREHOUSE", null,
                    "库存恒等式校验差异：" + runDate,
                    "发现 " + mismatches.size() + " 个维度不一致（总在手 ≠ 可用+冻结），"
                            + "已在可用库存页标记「待核实」。首条：" + mismatches.get(0),
                    "STOCK_CHECK", runDate.toString());
        }
        out.put("skipped", false);
        out.put("status", report.getStatus());
        out.put("mismatchCnt", mismatches.size());
        log.info("stock check {} done: {} mismatches", runDate, mismatches.size());
        return out;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> runDayClose() {
        LocalDate balDate = LocalDate.now().minusDays(1);   // 结算刚结束的一天
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balDate", balDate);
        if (dailyBalanceDao.countByBalDate(balDate) > 0) {
            out.put("skipped", true);
            out.put("inserted", 0);
            return out;
        }
        List<InvStock> stocks = stockDao.selectList(new LambdaQueryWrapper<InvStock>());
        int inserted = 0;
        for (InvStock s : stocks) {
            InvDailyBalance b = new InvDailyBalance();
            b.setBalDate(balDate);
            b.setWarehouseCode(s.getWarehouseCode());
            b.setItemCode(s.getItemCode());
            b.setItemName(s.getItemName());
            b.setBatchNo(s.getBatchNo());
            b.setBinCode(str0(s.getBinCode()));
            b.setQty(nvl(s.getQty()));
            b.setAvailableQty(nvl(s.getAvailableQty()));
            b.setQcQty(nvl(s.getQcQty()));
            b.setFinQty(nvl(s.getFinQty()));
            dailyBalanceDao.insert(b);
            inserted++;
        }
        out.put("skipped", false);
        out.put("inserted", inserted);
        log.info("stock day close {} done: {} rows", balDate, inserted);
        return out;
    }

    // ================= 帮助 =================

    /** 最近一次报告的差异维度键集合（解析失败按空集处理，不阻断查询） */
    private java.util.Set<String> pendingVerifyKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        InvCheckReport r = checkReportDao.selectLatest();
        if (r == null || !InvCheckReport.ST_MISMATCH.equals(r.getStatus())
                || isBlank(r.getDetailJson())) {
            return keys;
        }
        try {
            Object parsed = MAPPER.readValue(r.getDetailJson(), List.class);
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        keys.add(dimKey(str(m.get("warehouseCode")), str(m.get("itemCode")),
                                str(m.get("batchNo"))));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("校验报告明细解析失败（runDate={}）：{}", r.getRunDate(), e.getMessage());
        }
        return keys;
    }

    private String dimKey(String wh, String item, String batch) {
        return nvlStr(wh) + "|" + nvlStr(item) + "|" + nvlStr(batch);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String nvlStr(String s) {
        return s == null ? "" : s;
    }

    private String str(Object o) {
        return Objects.toString(o, null);
    }

    private String str0(String s) {
        return s == null ? "" : s;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

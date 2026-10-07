package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiCostSnapshotDao;
import com.erp.dao.bi.BiExtractTaskDao;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.entity.bi.BiCostSnapshot;
import com.erp.entity.bi.BiExtractTask;
import com.erp.entity.bi.BiPriceAlert;
import com.erp.security.IntfGuard;
import com.erp.service.bi.CostAnalysisService;
import com.erp.service.bi.ExtractService;
import com.erp.service.bi.MetricGuard;
import com.erp.service.bi.QueryGovernance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本分析查询实现。
 * 指标口径全部经 MetricGuard（未注册 422）；行级法人由 QueryGovernance 注入（未绑定空集+审计拒绝）；
 * 快照仅取最近 READY 批次（数据截止时间随响应返回）。
 */
@Slf4j
@Service
public class CostAnalysisServiceImpl implements CostAnalysisService {

    private static final DateTimeFormatter MT = DateTimeFormatter.ofPattern("yyyyMM");

    private final BiCostSnapshotDao snapshotDao;
    private final com.erp.dao.bi.AgreementPriceDao agreementPriceDao;
    private final com.erp.service.bi.PriceMonitorService priceMonitorService;
    private final BiPriceAlertDao alertDao;
    private final BiExtractTaskDao extractTaskDao;
    private final MetricGuard metricGuard;
    private final QueryGovernance governance;
    private final ExtractService extractService;

    @Value("${app.bi.price-trend-alert-pct:10}")
    private BigDecimal alertThreshold;

    public CostAnalysisServiceImpl(BiCostSnapshotDao snapshotDao, BiPriceAlertDao alertDao,
                                   BiExtractTaskDao extractTaskDao, MetricGuard metricGuard,
                                   QueryGovernance governance, ExtractService extractService,
                                   com.erp.dao.bi.AgreementPriceDao agreementPriceDao,
                                   com.erp.service.bi.PriceMonitorService priceMonitorService) {
        this.snapshotDao = snapshotDao;
        this.agreementPriceDao = agreementPriceDao;
        this.priceMonitorService = priceMonitorService;
        this.alertDao = alertDao;
        this.extractTaskDao = extractTaskDao;
        this.metricGuard = metricGuard;
        this.governance = governance;
        this.extractService = extractService;
    }

    // ------------------------------------------------------------ Tab1 成本构成

    @Override
    public Map<String, Object> composition(Map<String, Object> p) {
        metricGuard.requireAll("COST_PO_AMT", "COST_INV_AMT", "COST_DIFF_AMT",
                "COST_AMT_NO_TAX", "COST_TAX_AMT", "COST_FREIGHT", "COST_RETURN");
        List<String> allow = governanceAllowed("GET", "/api/bi/cost/composition", p);

        long current = longOf(p.get("current"), 1);
        long size = longOf(p.get("size"), 10);

        LambdaQueryWrapper<BiCostSnapshot> qw = baseQw(p);
        applyRow(qw, allow);
        long total = snapshotDao.selectCount(qw);

        LambdaQueryWrapper<BiCostSnapshot> qw2 = baseQw(p);
        applyRow(qw2, allow);
        qw2.orderByDesc(BiCostSnapshot::getMonthTag).orderByDesc(BiCostSnapshot::getPoAmt);
        qw2.last("LIMIT " + size + " OFFSET " + ((current - 1) * size));
        List<BiCostSnapshot> rows = snapshotDao.selectList(qw2);

        // 维度汇总（当前筛选全集）
        Map<String, Object> summary = summarize(allow, p);

        governance.auditQuery("/api/bi/cost/composition", p, List.of(), rows.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("records", rows);
        out.put("summary", summary);
        out.put("ready", extractService.readySnapshot());
        out.put("estimatingCount", rows.stream().filter(r -> Boolean.TRUE.equals(r.getEstimating())).count());
        return out;
    }

    private Map<String, Object> summarize(List<String> allow, Map<String, Object> p) {
        LambdaQueryWrapper<BiCostSnapshot> qw = baseQw(p);
        applyRow(qw, allow);
        BigDecimal po = BigDecimal.ZERO;
        BigDecimal inv = BigDecimal.ZERO;
        BigDecimal freight = BigDecimal.ZERO;
        BigDecimal ret = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        long estimating = 0;
        for (BiCostSnapshot s : snapshotDao.selectList(qw)) {
            po = po.add(nz(s.getPoAmt()));
            inv = inv.add(nz(s.getInvAmt()));
            freight = freight.add(nz(s.getFreightAmt()));
            ret = ret.add(nz(s.getReturnAmt()));
            tax = tax.add(nz(s.getTaxAmt()));
            if (Boolean.TRUE.equals(s.getEstimating())) {
                estimating++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poAmt", po);
        m.put("invAmt", inv);
        m.put("diffAmt", inv.subtract(po));
        m.put("taxAmt", tax);
        m.put("freightAmt", freight);
        m.put("returnAmt", ret);
        m.put("estimatingRows", estimating);
        return m;
    }

    @Override
    public Map<String, Object> priceDiff(String monthTag, String itemCode, String supplierId) {
        metricGuard.require("COST_DIFF_AMT");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("monthTag", monthTag);
        p.put("itemCode", itemCode);
        p.put("supplierId", supplierId);
        List<String> allow = governanceAllowed("GET", "/api/bi/cost/price-diff", p);

        LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
        qw.eq(monthTag != null && !monthTag.isEmpty(), BiCostSnapshot::getMonthTag, monthTag);
        qw.eq(itemCode != null && !itemCode.isEmpty(), BiCostSnapshot::getItemCode, itemCode);
        qw.eq(supplierId != null && !supplierId.isEmpty(), BiCostSnapshot::getSupplierId, supplierId);
        applyRow(qw, allow);

        List<BiCostSnapshot> all = snapshotDao.selectList(qw);
        long estimating = all.stream().filter(s -> Boolean.TRUE.equals(s.getEstimating())).count();
        // 价差明细剔除暂估行（spec：仅发票已入账）+ 剔除计数
        List<BiCostSnapshot> diffRows = all.stream()
                .filter(s -> !Boolean.TRUE.equals(s.getEstimating()) && s.getInvAmt() != null)
                .toList();

        governance.auditQuery("/api/bi/cost/price-diff", p, List.of(), diffRows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", diffRows);
        out.put("excludedEstimating", estimating);
        out.put("monthTag", monthTag);
        return out;
    }

    // ------------------------------------------------------------ Tab2 价格趋势

    @Override
    public Map<String, Object> trend(String itemCode, String basis) {
        metricGuard.requireAll("PRICE_TREND_AVG", "PRICE_TREND_MOM", "PRICE_TREND_YOY",
                "PRICE_TREND_MA3");
        if (itemCode == null || itemCode.trim().isEmpty()) {
            throw new ServiceException(400, "itemCode 必填");
        }
        boolean invoice = "INVOICE".equalsIgnoreCase(basis);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("itemCode", itemCode);
        p.put("basis", basis);
        List<String> allow = governanceAllowed("GET", "/api/bi/cost/trend", p);

        LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
        qw.eq(BiCostSnapshot::getItemCode, itemCode);
        applyRow(qw, allow);
        qw.orderByAsc(BiCostSnapshot::getMonthTag);

        // 月度均价（双口径）
        Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();   // month -> [amt, qty]
        String category = null;
        for (BiCostSnapshot s : snapshotDao.selectList(qw)) {
            BigDecimal amt = invoice ? nz(s.getInvAmt()) : nz(s.getPoAmt());
            BigDecimal qty = invoice ? nz(s.getInvQty()) : nz(s.getPoQty());
            if (qty.signum() <= 0) {
                continue;
            }
            byMonth.computeIfAbsent(s.getMonthTag(), k -> new BigDecimal[2]);
            BigDecimal[] cur = byMonth.get(s.getMonthTag());
            cur[0] = nz(cur[0]).add(amt);
            cur[1] = nz(cur[1]).add(qty);
            if (category == null) {
                category = s.getCategoryCode();
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : byMonth.entrySet()) {
            BigDecimal avg = e.getValue()[0].divide(e.getValue()[1], 4, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("monthTag", e.getKey());
            row.put("avgPrice", avg);
            row.put("qty", e.getValue()[1]);
            row.put("amt", e.getValue()[0]);
            series.add(row);
            prices.add(avg);
        }
        // 环比 / 同比 / MA3
        for (int i = 0; i < series.size(); i++) {
            Map<String, Object> row = series.get(i);
            BigDecimal cur = (BigDecimal) row.get("avgPrice");
            row.put("mom", i == 0 ? null : pct(prices.get(i), prices.get(i - 1)));
            row.put("yoy", i < 12 ? null : pct(cur, prices.get(i - 12)));
            if (i >= 2) {
                BigDecimal ma3 = prices.get(i).add(prices.get(i - 1)).add(prices.get(i - 2))
                        .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
                row.put("ma3", ma3);
            } else {
                row.put("ma3", null);
            }
        }

        // 协议价参考线（生效中的框架协议价，只读）
        List<Map<String, Object>> agreementLines = agreementPrice(itemCode);

        governance.auditQuery("/api/bi/cost/trend", p, List.of(), series.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemCode", itemCode);
        out.put("basis", invoice ? "INVOICE" : "PO");
        out.put("series", series);
        out.put("agreementPrices", agreementLines);
        out.put("categoryCode", category);
        out.put("ready", extractService.readySnapshot());
        return out;
    }

    /** 框架协议价参考线：生效协议行（只读，共享主数据不做行级过滤） */
    private List<Map<String, Object>> agreementPrice(String itemCode) {
        try {
            return agreementPriceDao.listByItem(itemCode);
        } catch (Exception e) {
            log.debug("agreement price lookup skipped: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------ Tab3 降本

    @Override
    public Map<String, Object> savings(String monthTag, String dimension) {
        metricGuard.requireAll("COST_SAVE_MOM", "COST_SAVE_YOY");
        if (monthTag == null || monthTag.trim().isEmpty()) {
            monthTag = YearMonth.now().minusMonths(1).format(MT);
        }
        String dim = dimension == null || dimension.trim().isEmpty() ? "SUPPLIER" : dimension.trim();
        if (!List.of("SUPPLIER", "CATEGORY", "BUYER").contains(dim)) {
            throw new ServiceException(400, "dimension 仅支持 SUPPLIER / CATEGORY / BUYER");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("monthTag", monthTag);
        p.put("dimension", dim);
        List<String> allow = governanceAllowed("GET", "/api/bi/cost/savings", p);

        LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
        qw.eq(BiCostSnapshot::getMonthTag, monthTag);
        applyRow(qw, allow);
        List<BiCostSnapshot> curr = snapshotDao.selectList(qw);

        String prevTag = prevMonth(monthTag);
        LambdaQueryWrapper<BiCostSnapshot> qwPrev = new LambdaQueryWrapper<>();
        qwPrev.eq(BiCostSnapshot::getMonthTag, prevTag);
        applyRow(qwPrev, allow);
        List<BiCostSnapshot> prev = snapshotDao.selectList(qwPrev);

        String lyTag = YearMonth.parse(monthTag, MT).minusYears(1).format(MT);   // 去年同月
        LambdaQueryWrapper<BiCostSnapshot> qwL = new LambdaQueryWrapper<>();
        qwL.eq(BiCostSnapshot::getMonthTag, lyTag);
        applyRow(qwL, allow);
        List<BiCostSnapshot> lastYear = snapshotDao.selectList(qwL);

        Map<String, BigDecimal[]> groups = new LinkedHashMap<>();   // key -> [saveMom, saveYoy]
        for (BiCostSnapshot s : curr) {
            String key = switch (dim) {
                case "CATEGORY" -> s.getCategoryCode();
                case "BUYER" -> s.getBuyer();
                default -> s.getSupplierId();
            };
            if (key == null) {
                key = "-";
            }
            BigDecimal currAvg = avgOf(s);
            BigDecimal prevAvg = findAvg(prev, dim, key, s.getItemCode());
            BigDecimal lyAvg = findAvg(lastYear, dim, key, s.getItemCode());
            BigDecimal qty = nz(s.getPoQty());

            groups.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] acc = groups.get(key);
            // 环比节约 = (上期均价 - 本期均价) × 本期量（spec 公式）
            if (prevAvg != null && prevAvg.signum() != 0) {
                acc[0] = nz(acc[0]).add(prevAvg.subtract(currAvg).multiply(qty));
            }
            if (lyAvg != null && lyAvg.signum() != 0) {
                acc[1] = nz(acc[1]).add(lyAvg.subtract(currAvg).multiply(qty));
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalMom = BigDecimal.ZERO;
        BigDecimal totalYoy = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", dim);
            row.put("key", e.getKey());
            row.put("saveMom", e.getValue()[0]);
            row.put("saveYoy", e.getValue()[1]);
            rows.add(row);
            totalMom = totalMom.add(e.getValue()[0]);
            totalYoy = totalYoy.add(e.getValue()[1]);
        }

        governance.auditQuery("/api/bi/cost/savings", p, List.of(), rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthTag", monthTag);
        out.put("dimension", dim);
        out.put("prevMonthTag", prevTag);
        out.put("records", rows);
        out.put("totalSaveMom", totalMom);
        out.put("totalSaveYoy", totalYoy);
        out.put("ready", extractService.readySnapshot());
        return out;
    }

    @Override
    public Map<String, Object> scanNow() {
        // 实时补算：对上一个完整月重跑异动判定（幂等 upsert 不触碰处置状态）
        YearMonth last = YearMonth.now().minusMonths(1);
        int n = alertDao.detectMonth(last.format(MT),
                priceMonitorService.thresholdValue("PRICE_TREND_ALERT_PCT", alertThreshold), "MANUAL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthTag", last.format(MT));
        out.put("detected", n);
        out.put("note", "已处置记录不被覆盖（同键仅回写数值）");
        return out;
    }

    // ------------------------------------------------------------ helpers

    /**
     * 行级权限：null=通配全量；空集=默认拒绝（spec 场景：返回空集并写拒绝审计，不抛错）。
     * 空集经 applyRow 限定 legalEntityId IN ('') 自然查空。
     */
    private List<String> governanceAllowed(String method, String path, Map<String, Object> params) {
        String user = IntfGuard.currentUser();
        List<String> allow = governance.allowedEntities(user);
        if (allow != null && allow.isEmpty()) {
            governance.auditQuery(path, params, List.of(), 0);   // 拒绝审计留痕
        }
        return allow;
    }

    private void applyRow(LambdaQueryWrapper<BiCostSnapshot> qw, List<String> allow) {
        if (allow != null) {
            qw.in(BiCostSnapshot::getLegalEntityId, allow.isEmpty() ? List.of("") : allow);
        }
    }

    private LambdaQueryWrapper<BiCostSnapshot> baseQw(Map<String, Object> p) {
        LambdaQueryWrapper<BiCostSnapshot> qw = new LambdaQueryWrapper<>();
        String monthTag = str(p.get("monthTag"));
        String itemCode = str(p.get("itemCode"));
        String supplierId = str(p.get("supplierId"));
        String category = str(p.get("categoryCode"));
        String buyer = str(p.get("buyer"));
        String legal = str(p.get("legalEntityId"));
        qw.eq(monthTag != null, BiCostSnapshot::getMonthTag, monthTag);
        qw.eq(itemCode != null, BiCostSnapshot::getItemCode, itemCode);
        qw.eq(supplierId != null, BiCostSnapshot::getSupplierId, supplierId);
        qw.eq(category != null, BiCostSnapshot::getCategoryCode, category);
        qw.eq(buyer != null, BiCostSnapshot::getBuyer, buyer);
        qw.eq(legal != null, BiCostSnapshot::getLegalEntityId, legal);
        return qw;
    }

    private BigDecimal avgOf(BiCostSnapshot s) {
        BigDecimal qty = nz(s.getPoQty());
        if (qty.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return nz(s.getPoAmt()).divide(qty, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal findAvg(List<BiCostSnapshot> rows, String dim, String key, String itemCode) {
        for (BiCostSnapshot s : rows) {
            String k = switch (dim) {
                case "CATEGORY" -> s.getCategoryCode();
                case "BUYER" -> s.getBuyer();
                default -> s.getSupplierId();
            };
            if (key.equals(k) && itemCode.equals(s.getItemCode())) {
                return avgOf(s);
            }
        }
        return null;
    }

    private static BigDecimal pct(BigDecimal curr, BigDecimal prev) {
        if (prev == null || prev.signum() == 0) {
            return null;
        }
        return curr.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String prevMonth(String monthTag) {
        YearMonth ym = YearMonth.parse(monthTag, MT).minusMonths(1);
        return ym.format(MT);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static long longOf(Object v, long def) {
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

}

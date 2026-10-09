package com.erp.service.impl.inv;

import com.erp.common.ServiceException;
import com.erp.service.SysParamService;
import com.erp.service.inv.InvReportService;
import com.erp.service.inv.StockSnapshotService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存报表实现（4.14，spec inventory-reports；design D1~D7）。
 * 只读 SQL 聚合（JdbcTemplate，沿 OutboundWorkbench/Wave 先例）：
 * 实时位行（inv_stock 直查非快照，D2）、周转分子（OUT 流水×标准成本）/ 分母（日结快照均值，
 * 缺失降级当前库存金额 D3）、库龄五桶 + 呆滞识别、呆滞每日推送（幂等=物料+日）。
 */
@Slf4j
@Service
public class InvReportServiceImpl implements InvReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 库龄桶边界（spec 库龄分析） */
    private static final String BUCKET_SQL =
            "CASE WHEN s.INBOUND_DATE IS NULL THEN 'UNKNOWN' "
                    + "WHEN DATEDIFF(CURDATE(), s.INBOUND_DATE) <= 30 THEN '0-30' "
                    + "WHEN DATEDIFF(CURDATE(), s.INBOUND_DATE) <= 60 THEN '31-60' "
                    + "WHEN DATEDIFF(CURDATE(), s.INBOUND_DATE) <= 90 THEN '61-90' "
                    + "WHEN DATEDIFF(CURDATE(), s.INBOUND_DATE) <= 180 THEN '91-180' "
                    + "ELSE '>180' END";

    private final JdbcTemplate jdbc;
    private final SysParamService sysParamService;
    private final NoticeService noticeService;
    private final StockSnapshotService stockSnapshotService;

    public InvReportServiceImpl(JdbcTemplate jdbc, SysParamService sysParamService,
                                NoticeService noticeService, StockSnapshotService stockSnapshotService) {
        this.jdbc = jdbc;
        this.sysParamService = sysParamService;
        this.noticeService = noticeService;
        this.stockSnapshotService = stockSnapshotService;
    }

    // ================= 4.14.1 实时查询 =================

    @Override
    public Map<String, Object> realtime(String warehouseCode, String itemCode, String batchNo,
                                        String keyword, String abcClass, long current, long size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendStockFilters(where, args, warehouseCode, itemCode, batchNo, keyword, abcClass);

        long limit = Math.max(size, 1);
        long offset = (Math.max(current, 1) - 1) * limit;
        // 库龄降序：NULL（未知）靠后（ORDER BY INBOUND_DATE IS NULL, INBOUND_DATE ASC 等价）
        String sql = "SELECT s.ID, s.WAREHOUSE_CODE, s.ITEM_CODE, s.ITEM_NAME, s.BATCH_NO, s.BIN_CODE, "
                + "s.QTY, s.QC_QTY, s.FIN_QTY, s.AVAILABLE_QTY, s.INBOUND_DATE, i.ABC_CLASS, "
                + "CASE WHEN s.INBOUND_DATE IS NULL THEN NULL "
                + "ELSE DATEDIFF(CURDATE(), s.INBOUND_DATE) END AS AGE_DAYS "
                + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE"
                + where + " ORDER BY (s.INBOUND_DATE IS NULL), DATEDIFF(CURDATE(), s.INBOUND_DATE) DESC "
                + "LIMIT " + limit + " OFFSET " + offset;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());

        String totalSql = "SELECT COUNT(*) CNT, COALESCE(SUM(s.QTY),0) T_QTY, "
                + "COALESCE(SUM(s.QC_QTY + s.FIN_QTY),0) T_FROZEN, COALESCE(SUM(s.AVAILABLE_QTY),0) T_AVAIL "
                + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE" + where;
        Map<String, Object> totals = jdbc.queryForMap(totalSql, args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", totals.get("CNT"));
        out.put("totals", Map.of(
                "qty", totals.get("T_QTY"),
                "frozen", totals.get("T_FROZEN"),
                "available", totals.get("T_AVAIL")));
        out.put("asOf", LocalDate.now().format(DAY));
        return out;
    }

    // ================= 4.14.2 周转 =================

    @Override
    public Map<String, Object> turnover(String from, String to, String scope) {
        LocalDate fromD = parseDate(from, LocalDate.now().minusDays(29));
        LocalDate toD = parseDate(to, LocalDate.now());
        if (fromD.isAfter(toD)) {
            throw new ServiceException(422, "开始日期不能晚于结束日期");
        }
        String sc = SCOPE_ALL_OUT.equalsIgnoreCase(scope) ? SCOPE_ALL_OUT : SCOPE_SALES;
        long periodDays = toD.toEpochDay() - fromD.toEpochDay() + 1;

        // 分子：期间 OUT 流水 × 标准成本（M-WMS-001 分子口径可切换）
        Double numerator = jdbc.queryForObject(
                "SELECT COALESCE(SUM(t.QTY * COALESCE(i.STANDARD_COST,0)),0) "
                        + "FROM erp_inv_transaction t LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = t.ITEM_CODE "
                        + "WHERE t.DIRECTION = 'OUT' "
                        + (SCOPE_SALES.equals(sc) ? "AND t.TYPE_CODE = 'SALES_OUT' " : "")
                        + "AND t.CREATE_DATE >= ? AND t.CREATE_DATE < ?",
                Double.class, fromD.toString(), toD.plusDays(1).toString());

        // 分母：期间日结快照逐日库存金额均值（design D3）
        Map<String, Object> den = denominator(fromD, toD);
        boolean degraded = "DEGRADED".equals(den.get("mode"));
        boolean noSnap = "NONE".equals(den.get("mode"));

        BigDecimalRate r = rateOf(numerator, den.get("value"), periodDays, degraded, noSnap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromD.toString());
        out.put("to", toD.toString());
        out.put("periodDays", periodDays);
        out.put("scope", sc);
        out.put("scopeLabel", SCOPE_SALES.equals(sc) ? "销售成本（指标字典口径）" : "全部出库（非指标字典口径）");
        out.put("numerator", numerator);
        out.put("denominator", den.get("value"));
        out.put("denominatorMode", den.get("mode"));     // SNAPSHOT / DEGRADED / NONE
        out.put("snapshotDays", den.get("snapshotDays"));
        out.put("degraded", degraded);
        out.put("degradedReason", degraded
                ? "期间无日结快照，分母降级为当前库存金额（数据积累中，补数后逐日转标准口径）" : null);
        out.put("rate", r.rate);                          // null=不适用
        out.put("turnoverDays", r.days);
        out.put("staleRatio", currentStaleRatio());
        return out;
    }

    /** 分母三态：SNAPSHOT 均值 / DEGRADED 当前库存金额 / NONE 分母 0 */
    private Map<String, Object> denominator(LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> snap = jdbc.queryForMap(
                "SELECT COUNT(DISTINCT BAL_DATE) D, COALESCE(AVG(day_val),0) V FROM ("
                        + "SELECT b.BAL_DATE, SUM(b.QTY * COALESCE(i.STANDARD_COST,0)) day_val "
                        + "FROM erp_inv_daily_balance b LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = b.ITEM_CODE "
                        + "WHERE b.BAL_DATE >= ? AND b.BAL_DATE <= ? GROUP BY b.BAL_DATE) x",
                from.toString(), to.toString());
        long days = ((Number) snap.get("D")).longValue();
        out.put("snapshotDays", days);
        if (days >= 1) {
            out.put("mode", "SNAPSHOT");
            out.put("value", snap.get("V"));
            return out;
        }
        Double current = jdbc.queryForObject(
                "SELECT COALESCE(SUM(s.QTY * COALESCE(i.STANDARD_COST,0)),0) "
                        + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE",
                Double.class);
        if (current != null && current > 0) {
            out.put("mode", "DEGRADED");
            out.put("value", current);
        } else {
            out.put("mode", "NONE");
            out.put("value", 0d);
        }
        return out;
    }

    /** rate/days：分母 0 或缺失 → null（「不适用」，spec 除零保护）；degraded 由调用方标注 */
    private BigDecimalRate rateOf(Double numerator, Object denominator, long periodDays,
                                  boolean degraded, boolean noSnap) {
        BigDecimalRate r = new BigDecimalRate();
        double den = denominator == null ? 0 : ((Number) denominator).doubleValue();
        double num = numerator == null ? 0 : numerator;
        if (den <= 0) {
            r.rate = null;
            r.days = null;
            return r;
        }
        r.rate = num / den;
        r.days = (r.rate > 0 && periodDays > 0) ? periodDays / r.rate : null;
        if (noSnap) {
            r.rate = null;
            r.days = null;
        }
        return r;
    }

    private static final class BigDecimalRate {
        Double rate;
        Double days;
    }

    @Override
    public List<Map<String, Object>> turnoverSummary(String from, String to, String scope,
                                                     String groupBy) {
        LocalDate fromD = parseDate(from, LocalDate.now().minusDays(29));
        LocalDate toD = parseDate(to, LocalDate.now());
        String sc = SCOPE_ALL_OUT.equalsIgnoreCase(scope) ? SCOPE_ALL_OUT : SCOPE_SALES;
        String gTxn = groupExpr(groupBy, "t");
        String gBal = groupExpr(groupBy, "b");
        String gStk = groupExpr(groupBy, "s");

        // 分子按组
        Map<String, Double> num = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT " + gTxn + " G, COALESCE(SUM(t.QTY * COALESCE(i.STANDARD_COST,0)),0) V "
                        + "FROM erp_inv_transaction t LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = t.ITEM_CODE "
                        + "WHERE t.DIRECTION = 'OUT' "
                        + (SCOPE_SALES.equals(sc) ? "AND t.TYPE_CODE = 'SALES_OUT' " : "")
                        + "AND t.CREATE_DATE >= ? AND t.CREATE_DATE < ? GROUP BY G",
                fromD.toString(), toD.plusDays(1).toString())) {
            num.put(str(row.get("G")), ((Number) row.get("V")).doubleValue());
        }
        // 分母按组（期间日均库存金额）
        Map<String, Double> den = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT G, AVG(day_val) V FROM (SELECT " + gBal
                        + " G, b.BAL_DATE, SUM(b.QTY * COALESCE(i.STANDARD_COST,0)) day_val "
                        + "FROM erp_inv_daily_balance b LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = b.ITEM_CODE "
                        + "WHERE b.BAL_DATE >= ? AND b.BAL_DATE <= ? GROUP BY G, b.BAL_DATE) x GROUP BY G",
                fromD.toString(), toD.toString())) {
            den.put(str(row.get("G")), ((Number) row.get("V")).doubleValue());
        }

        // 当前库存金额按组（分母缺失时的补充信息）
        Map<String, Double> current = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT " + gStk + " G, COALESCE(SUM(s.QTY * COALESCE(i.STANDARD_COST,0)),0) V "
                        + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE "
                        + "GROUP BY G")) {
            current.put(str(row.get("G")), ((Number) row.get("V")).doubleValue());
        }

        long periodDays = toD.toEpochDay() - fromD.toEpochDay() + 1;
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(num.keySet());
        keys.addAll(den.keySet());
        keys.addAll(current.keySet());
        for (String k : keys) {
            if (k.isEmpty()) {
                continue;
            }
            Double n = num.getOrDefault(k, 0d);
            Double d = den.get(k);                       // 无快照 → null（页面标注）
            Double cur = current.getOrDefault(k, 0d);
            Double eff = (d == null || d <= 0) ? (cur > 0 ? cur : 0d) : d;
            Double rate = eff > 0 ? n / eff : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupKey", k);
            m.put("outCost", n);
            m.put("avgStockValue", d);                   // null=期间无快照
            m.put("currentStockValue", cur);
            m.put("denominatorMode", d == null ? "DEGRADED" : (d <= 0 ? "NONE" : "SNAPSHOT"));
            m.put("rate", rate);
            m.put("turnoverDays", rate != null && rate > 0 ? periodDays / rate : null);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare(
                ((Number) b.get("outCost")).doubleValue(),
                ((Number) a.get("outCost")).doubleValue()));
        return out;
    }

    @Override
    public List<Map<String, Object>> turnoverTrend(int months) {
        int n = Math.min(Math.max(months, 1), 24);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate mStart = today.withDayOfMonth(1).minusMonths(i);
            LocalDate mEnd = mStart.withDayOfMonth(mStart.lengthOfMonth());
            LocalDate effEnd = mEnd.isAfter(today) ? today : mEnd;
            // 缺快照月份不输出点（spec 趋势断开由前端 connectNulls:false 承载）
            Long snapDays = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT BAL_DATE) FROM erp_inv_daily_balance "
                            + "WHERE BAL_DATE >= ? AND BAL_DATE <= ?",
                    Long.class, mStart.toString(), effEnd.toString());
            if (snapDays == null || snapDays < 1) {
                continue;
            }
            Map<String, Object> t = turnover(mStart.toString(), effEnd.toString(), SCOPE_SALES);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", mStart.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            point.put("rate", t.get("rate"));
            point.put("days", t.get("turnoverDays"));
            point.put("degraded", t.get("degraded"));
            out.add(point);
        }
        return out;
    }

    @Override
    public Map<String, Object> backfillDayClose() {
        // spec：幂等回补最近一个缺失日的日结快照（runDayClose 补昨天、按日幂等）
        return stockSnapshotService.runDayClose();
    }

    // ================= 5.1/5.2 库龄与呆滞 =================

    @Override
    public Map<String, Object> agingBuckets(String warehouseCode, String itemCode, String abcClass) {
        int threshold = sysParamService.getInt("SLOW_MOVING_AGE_DAYS", 180);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendStockFilters(where, args, warehouseCode, itemCode, null, null, abcClass);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + BUCKET_SQL + " B, COUNT(*) CNT, "
                        + "COALESCE(SUM(s.QTY * COALESCE(i.STANDARD_COST,0)),0) AMT, "
                        + "COALESCE(SUM(CASE WHEN s.INBOUND_DATE IS NOT NULL "
                        + "AND DATEDIFF(CURDATE(), s.INBOUND_DATE) > ? "
                        + "THEN s.QTY * COALESCE(i.STANDARD_COST,0) ELSE 0 END),0) STALE_AMT "
                        + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE"
                        + where + " GROUP BY B",
                // 阈值占位符在 SELECT 子句（先于 WHERE 占位符），须放参数首位
                prepend(args, threshold).toArray());

        List<Map<String, Object>> buckets = new ArrayList<>();
        Map<String, String> labels = Map.of(
                "0-30", "0-30 天", "31-60", "31-60 天", "61-90", "61-90 天",
                "91-180", "91-180 天", ">180", ">180 天（呆滞区）", "UNKNOWN", "未知库龄");
        long totalCnt = 0;
        double totalAmt = 0;
        double staleAmt = 0;
        long unknownCnt = 0;
        for (Map<String, Object> r : rows) {
            String key = str(r.get("B"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", key);
            m.put("label", labels.getOrDefault(key, key));
            m.put("count", ((Number) r.get("CNT")).longValue());
            m.put("amount", r.get("AMT"));
            m.put("stale", ">180".equals(key));
            buckets.add(m);
            totalCnt += ((Number) r.get("CNT")).longValue();
            totalAmt += ((Number) r.get("AMT")).doubleValue();
            staleAmt += ((Number) r.get("STALE_AMT")).doubleValue();
            if ("UNKNOWN".equals(key)) {
                unknownCnt = ((Number) r.get("CNT")).longValue();
            }
        }
        buckets.sort((a, b) -> bucketOrder(str(a.get("bucket"))) - bucketOrder(str(b.get("bucket"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", buckets);
        out.put("total", Map.of("count", totalCnt, "amount", totalAmt));
        out.put("unknownCount", unknownCnt);
        out.put("staleThreshold", threshold);
        out.put("staleAmount", staleAmt);
        out.put("staleRatio", totalAmt > 0 ? staleAmt / totalAmt * 100 : 0d);
        return out;
    }

    @Override
    public Map<String, Object> agingList(String warehouseCode, String itemCode, String abcClass,
                                         boolean staleOnly, long current, long size) {
        int threshold = sysParamService.getInt("SLOW_MOVING_AGE_DAYS", 180);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendStockFilters(where, args, warehouseCode, itemCode, null, null, abcClass);
        if (staleOnly) {
            where.append(" AND s.INBOUND_DATE IS NOT NULL AND DATEDIFF(CURDATE(), s.INBOUND_DATE) > ?");
            args.add(threshold);
        }
        long limit = Math.max(size, 1);
        long offset = (Math.max(current, 1) - 1) * limit;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.WAREHOUSE_CODE, s.ITEM_CODE, s.ITEM_NAME, s.BATCH_NO, s.BIN_CODE, "
                        + "s.QTY, s.INBOUND_DATE, i.ABC_CLASS, i.STANDARD_COST, "
                        + "CASE WHEN s.INBOUND_DATE IS NULL THEN NULL "
                        + "ELSE DATEDIFF(CURDATE(), s.INBOUND_DATE) END AS AGE_DAYS, "
                        + "s.QTY * COALESCE(i.STANDARD_COST,0) AS AMT "
                        + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE"
                        + where + " ORDER BY (s.INBOUND_DATE IS NULL), DATEDIFF(CURDATE(), s.INBOUND_DATE) DESC "
                        + "LIMIT " + limit + " OFFSET " + offset, args.toArray());
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE"
                        + where, Long.class, args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total == null ? 0 : total);
        out.put("staleThreshold", threshold);
        return out;
    }

    // ================= 6.1 呆滞每日推送 =================

    @Override
    public int dailySlowMovingScan() {
        try {
            int threshold = sysParamService.getInt("SLOW_MOVING_AGE_DAYS", 180);
            String today = LocalDate.now().format(DAY);
            List<Map<String, Object>> stale = jdbc.queryForList(
                    "SELECT s.ITEM_CODE, MAX(i.ITEM_NAME) ITEM_NAME, "
                            + "MAX(DATEDIFF(CURDATE(), s.INBOUND_DATE)) MAX_AGE, "
                            + "COALESCE(SUM(s.QTY * COALESCE(i.STANDARD_COST,0)),0) AMT "
                            + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE "
                            + "WHERE s.INBOUND_DATE IS NOT NULL AND DATEDIFF(CURDATE(), s.INBOUND_DATE) > ? "
                            + "GROUP BY s.ITEM_CODE", threshold);
            int pushed = 0;
            for (Map<String, Object> r : stale) {
                String itemCode = str(r.get("ITEM_CODE"));
                // 幂等键 = 物料+日期（NoticeService 同 BIZ_TYPE+BIZ_ID 未读不重复推）
                noticeService.push("ROLE_WAREHOUSE", null,
                        "呆滞库存提醒：" + str(r.get("ITEM_NAME")),
                        "物料 " + itemCode + " 最长库龄 " + r.get("MAX_AGE") + " 天（阈值 "
                                + threshold + " 天），呆滞金额 " + r.get("AMT")
                                + "。请评估报废（三方会签）/调拨/促销处置。",
                        "SLOW_MOVING", itemCode + ":" + today);
                pushed++;
            }
            if (!stale.isEmpty()) {
                log.info("slow-moving daily scan: {} item(s) notified (threshold={})",
                        stale.size(), threshold);
            }
            return pushed;
        } catch (Exception e) {
            // spec：扫描失败不阻断日结既有任务
            log.warn("slow-moving scan failed: {}", e.getMessage());
            return 0;
        }
    }

    // ================= 7.1 导出行数据源 =================

    @Override
    public Map<String, Object> exportRows(String type, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        long maxRows = p.get("maxRows") == null ? Long.MAX_VALUE
                : ((Number) p.get("maxRows")).longValue();
        String t = str(type);
        List<String> headers;
        List<List<Object>> rows = new ArrayList<>();

        if ("realtime".equals(t)) {
            headers = List.of("仓库", "物料编码", "物料名称", "批次", "仓位", "在手", "冻结",
                    "可用", "入库日期", "库龄(天)", "ABC");
            Map<String, Object> data = realtime(str(p.get("warehouseCode")), str(p.get("itemCode")),
                    str(p.get("batchNo")), str(p.get("keyword")), str(p.get("abcClass")),
                    1, maxRows);
            for (Map<String, Object> r : castList(data.get("records"))) {
                rows.add(java.util.Arrays.asList(r.get("WAREHOUSE_CODE"), r.get("ITEM_CODE"), r.get("ITEM_NAME"),
                        r.get("BATCH_NO"), r.get("BIN_CODE"), r.get("QTY"), r.get("QC_QTY"),
                        r.get("AVAILABLE_QTY"), r.get("INBOUND_DATE"), r.get("AGE_DAYS"),
                        r.get("ABC_CLASS")));
            }
        } else if ("aging".equals(t)) {
            headers = List.of("仓库", "物料编码", "物料名称", "批次", "仓位", "数量", "入库日期",
                    "库龄(天)", "ABC", "金额", "呆滞");
            boolean staleOnly = Boolean.parseBoolean(str(p.get("staleOnly")));
            Map<String, Object> data = agingList(str(p.get("warehouseCode")), str(p.get("itemCode")),
                    str(p.get("abcClass")), staleOnly, 1, maxRows);
            int threshold = sysParamService.getInt("SLOW_MOVING_AGE_DAYS", 180);
            for (Map<String, Object> r : castList(data.get("records"))) {
                Object age = r.get("AGE_DAYS");
                boolean stale = age != null && ((Number) age).intValue() > threshold;
                rows.add(java.util.Arrays.asList(r.get("WAREHOUSE_CODE"), r.get("ITEM_CODE"), r.get("ITEM_NAME"),
                        r.get("BATCH_NO"), r.get("BIN_CODE"), r.get("QTY"), r.get("INBOUND_DATE"),
                        age, r.get("ABC_CLASS"), r.get("AMT"), stale ? "是" : ""));
            }
        } else if ("turnover".equals(t)) {
            headers = List.of("分组", "期间出库成本", "日均库存金额", "当前库存金额", "分母口径",
                    "周转率", "周转天数");
            for (Map<String, Object> r : turnoverSummary(str(p.get("from")), str(p.get("to")),
                    str(p.get("scope")), str(p.get("groupBy")))) {
                rows.add(java.util.Arrays.asList(r.get("groupKey"), r.get("outCost"), r.get("avgStockValue"),
                        r.get("currentStockValue"), r.get("denominatorMode"), r.get("rate"),
                        r.get("turnoverDays")));
            }
        } else {
            throw new ServiceException(422, "未知导出类型：" + type);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", headers);
        out.put("rows", rows);
        out.put("truncated", rows.size() >= maxRows);
        return out;
    }

    @Override
    public Map<String, Object> currentStockValue(String warehouseCode, String itemCode,
                                                 String abcClass) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendStockFilters(where, args, warehouseCode, itemCode, null, null, abcClass);
        return jdbc.queryForMap(
                "SELECT COUNT(*) CNT, COALESCE(SUM(s.QTY * COALESCE(i.STANDARD_COST,0)),0) AMT "
                        + "FROM erp_inv_stock s LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = s.ITEM_CODE"
                        + where, args.toArray());
    }

    /** 当前呆滞占比（周转指标卡第三卡，spec 汇总与趋势）：呆滞金额/总库存金额 ×100 */
    private Double currentStaleRatio() {
        Map<String, Object> b = agingBuckets(null, null, null);
        double stale = ((Number) b.get("staleAmount")).doubleValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) b.get("total");
        double amt = ((Number) total.get("amount")).doubleValue();
        return amt > 0 ? stale / amt * 100 : 0d;
    }

    // ---------- helpers ----------

    private void appendStockFilters(StringBuilder where, List<Object> args,
                                    String warehouseCode, String itemCode, String batchNo,
                                    String keyword, String abcClass) {
        if (!isBlank(warehouseCode)) {
            where.append(" AND s.WAREHOUSE_CODE = ?");
            args.add(warehouseCode.trim());
        }
        if (!isBlank(itemCode)) {
            where.append(" AND s.ITEM_CODE = ?");
            args.add(itemCode.trim());
        }
        if (!isBlank(batchNo)) {
            where.append(" AND s.BATCH_NO = ?");
            args.add(batchNo.trim());
        }
        if (!isBlank(keyword)) {
            where.append(" AND (s.ITEM_CODE LIKE ? OR s.ITEM_NAME LIKE ? OR s.BATCH_NO LIKE ? "
                    + "OR s.BIN_CODE LIKE ?)");
            String k = "%" + keyword.trim() + "%";
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
        }
        if (!isBlank(abcClass)) {
            if ("NONE".equalsIgnoreCase(abcClass.trim())) {
                where.append(" AND (i.ABC_CLASS IS NULL OR i.ABC_CLASS = '')");
            } else {
                where.append(" AND i.ABC_CLASS = ?");
                args.add(abcClass.trim().toUpperCase());
            }
        }
    }

    /** 分组表达式按查询别名生成：alias = t（流水）/ b（快照）/ s（当前库存）；ABC 恒用 i（物料） */
    private String groupExpr(String groupBy, String alias) {
        String g = isBlank(groupBy) ? "ITEM" : groupBy.trim().toUpperCase();
        return switch (g) {
            case "WAREHOUSE" -> alias + ".WAREHOUSE_CODE";
            case "ABC" -> "IFNULL(i.ABC_CLASS, '未分类')";
            default -> alias + ".ITEM_CODE";
        };
    }

    private static int bucketOrder(String key) {
        return switch (key) {
            case "0-30" -> 1;
            case "31-60" -> 2;
            case "61-90" -> 3;
            case "91-180" -> 4;
            case ">180" -> 5;
            default -> 9;
        };
    }

    private static LocalDate parseDate(String s, LocalDate def) {
        if (isBlank(s)) {
            return def;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private static List<Object> prepend(List<Object> args, Object first) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(args);
        return all;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

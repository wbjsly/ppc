package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamCountPlanDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.dao.eam.EamReportDao;
import com.erp.entity.eam.EamCountPlan;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.service.SysParamService;
import com.erp.service.eam.EamReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产报表实现（add-asset-report，design D2~D6）：纯只读查询，无事务无状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EamReportServiceImpl implements EamReportService {

    private final EamReportDao reportDao;
    private final EamCountPlanDao countPlanDao;
    private final EamDepreciationRunDao runDao;
    private final SysParamService sysParamService;

    // ================= 7.8.1 变动报表 =================

    @Override
    public Map<String, Object> changeEvents(long current, long size, String fromDate, String toDate,
                                            String evtType, String categoryCode, String costCenter) {
        long total = reportDao.changeEventCount(fromDate, toDate, evtType, categoryCode, costCenter);
        List<Map<String, Object>> rows = reportDao.changeEvents(fromDate, toDate, evtType,
                categoryCode, costCenter, (current - 1) * size, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("records", rows);
        return out;
    }

    @Override
    public Map<String, Object> changeSummary(String fromDate, String toDate) {
        String from = hasText(fromDate) ? fromDate : LocalDate.now().withDayOfMonth(1).toString();
        String to = hasText(toDate) ? toDate : LocalDate.now().toString();
        // 事件口径：按类型分组的 signed 合计（+增/−减/0）
        List<Map<String, Object>> totals = reportDao.changeEventTotals(from, to);
        BigDecimal eventInc = BigDecimal.ZERO;
        BigDecimal eventDec = BigDecimal.ZERO;
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map<String, Object> t : totals) {
            BigDecimal amt = nz((BigDecimal) t.get("TOTAL"));
            byType.put(String.valueOf(t.get("EVT_TYPE")), amt);
            if (amt.signum() > 0) {
                eventInc = eventInc.add(amt);
            } else {
                eventDec = eventDec.add(amt);   // 负值累计
            }
        }
        // 快照口径：当前在册 + 期间增减重算
        Map<String, Object> snap = reportDao.bookSnapshot(from, to);
        BigDecimal bookCost = nz((BigDecimal) snap.get("BOOK_COST"));
        BigDecimal bookAccum = nz((BigDecimal) snap.get("BOOK_ACCUM"));
        BigDecimal snapInc = nz((BigDecimal) snap.get("SNAP_INC"));
        BigDecimal snapDec = nz((BigDecimal) snap.get("SNAP_DEC_DISPOSAL"))
                .add(nz((BigDecimal) snap.get("SNAP_DEC_REPLACED")));
        // 期初 = 当前 − 期间增加 + 期间减少（快照口径重算，design D3 空基线）
        BigDecimal openCost = bookCost.subtract(snapInc).add(snapDec);
        BigDecimal openAccum = bookAccum.subtract(deprInPeriod(from, to)).add(snapDecAccum(from, to));
        // 勾对：事件口径 vs 快照口径差额（L4 标注，不阻断）
        BigDecimal tol = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        BigDecimal diffInc = eventInc.subtract(snapInc).abs();
        BigDecimal diffDec = eventDec.negate().subtract(snapDec).abs();
        boolean reconOk = diffInc.compareTo(tol) <= 0 && diffDec.compareTo(tol) <= 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromDate", from);
        out.put("toDate", to);
        out.put("openCost", openCost.setScale(2, RoundingMode.HALF_UP));
        out.put("increase", snapInc.setScale(2, RoundingMode.HALF_UP));
        out.put("decrease", snapDec.setScale(2, RoundingMode.HALF_UP));
        out.put("closeCost", bookCost.setScale(2, RoundingMode.HALF_UP));
        out.put("openAccum", openAccum.setScale(2, RoundingMode.HALF_UP));
        out.put("closeAccum", bookAccum.setScale(2, RoundingMode.HALF_UP));
        out.put("eventInc", eventInc.setScale(2, RoundingMode.HALF_UP));
        out.put("eventDec", eventDec.setScale(2, RoundingMode.HALF_UP));
        out.put("diffInc", diffInc.setScale(2, RoundingMode.HALF_UP));
        out.put("diffDec", diffDec.setScale(2, RoundingMode.HALF_UP));
        out.put("reconOk", reconOk);
        out.put("reconNote", reconOk ? "勾对平衡"
                : "勾对异常：事件口径与卡片快照口径差额（增加侧 " + diffInc + " / 减少侧 " + diffDec
                        + "），存量卡片可能缺事件源（迁移前无转资/处置单据）");
        out.put("byType", byType);
        return out;
    }

    /** 期间折旧计提合计（快照侧期初累计折旧重算用） */
    private BigDecimal deprInPeriod(String from, String to) {
        List<EamDepreciationRun> runs = runDao.selectList(new LambdaQueryWrapper<EamDepreciationRun>()
                .eq(EamDepreciationRun::getDelFlag, "0")
                .eq(EamDepreciationRun::getReversedFlag, "0"));
        BigDecimal sum = BigDecimal.ZERO;
        for (EamDepreciationRun r : runs) {
            String period = r.getPeriodTag();
            LocalDate start = LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                    Integer.parseInt(period.substring(4, 6)), 1);
            if (!start.isBefore(LocalDate.parse(from)) && !start.isAfter(LocalDate.parse(to))) {
                sum = sum.add(nz(r.getTotalDepr()));
            }
        }
        return sum;
    }

    private BigDecimal snapDecAccum(String from, String to) {
        // 处置转出的累计折旧：期间处置卡快照口径（近似——处置行 ACCUM_DEPR 合计）
        Map<String, Object> m = reportDao.bookSnapshot(from, to);
        return nz((BigDecimal) m.get("SNAP_DEC_DISPOSAL"));   // 金额量级近似（L4 报表口径，非账务）
    }

    // ================= 7.8.2 折旧报表 =================

    @Override
    public Map<String, Object> depreciationSummary(String periodTag) {
        if (!hasText(periodTag)) {
            periodTag = YearMonth.now().toString().replace("-", "");
        }
        String prev = prevPeriod(periodTag);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodTag", periodTag);
        out.put("byCategory", mergeWithPrev(
                reportDao.depreciationSummaryBy(periodTag, false),
                reportDao.depreciationSummaryBy(prev, false)));
        out.put("byCostCenter", mergeWithPrev(
                reportDao.depreciationSummaryBy(periodTag, true),
                reportDao.depreciationSummaryBy(prev, true)));
        EamDepreciationRun run = runDao.selectOne(new LambdaQueryWrapper<EamDepreciationRun>()
                .eq(EamDepreciationRun::getPeriodTag, periodTag)
                .eq(EamDepreciationRun::getDelFlag, "0")
                .orderByDesc(EamDepreciationRun::getCreateDate).last("LIMIT 1"));
        Map<String, Object> head = new LinkedHashMap<>();
        if (run != null) {
            head.put("runNo", run.getRunNo());
            head.put("status", run.getStatus());
            head.put("totalDepr", run.getTotalDepr());
            head.put("reconDiff", run.getReconDiff());
            head.put("reversedFlag", run.getReversedFlag());
        }
        out.put("run", head);
        return out;
    }

    /** 当期维度行 + 上期对比 + 环比差异/超容差 alerted（FR-4.13-2-3） */
    private List<Map<String, Object>> mergeWithPrev(List<Map<String, Object>> cur,
                                                    List<Map<String, Object>> prev) {
        Map<String, BigDecimal> prevMap = new LinkedHashMap<>();
        for (Map<String, Object> p : prev) {
            prevMap.put(String.valueOf(p.get("dim")), nz((BigDecimal) p.get("AMT")));
        }
        BigDecimal tol = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        BigDecimal totalCur = BigDecimal.ZERO;
        BigDecimal totalPrev = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : cur) {
            String dim = String.valueOf(c.get("dim"));
            BigDecimal amt = nz((BigDecimal) c.get("AMT"));
            BigDecimal prevAmt = prevMap.getOrDefault(dim, BigDecimal.ZERO);
            BigDecimal diff = amt.subtract(prevAmt);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dim", dim);
            row.put("curAmt", amt.setScale(2, RoundingMode.HALF_UP));
            row.put("prevAmt", prevAmt.setScale(2, RoundingMode.HALF_UP));
            row.put("diff", diff.setScale(2, RoundingMode.HALF_UP));
            row.put("alerted", diff.abs().compareTo(tol) > 0);
            out.add(row);
            totalCur = totalCur.add(amt);
            totalPrev = totalPrev.add(prevAmt);
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("dim", "合计");
        total.put("curAmt", totalCur.setScale(2, RoundingMode.HALF_UP));
        total.put("prevAmt", totalPrev.setScale(2, RoundingMode.HALF_UP));
        total.put("diff", totalCur.subtract(totalPrev).setScale(2, RoundingMode.HALF_UP));
        total.put("alerted", totalCur.subtract(totalPrev).abs().compareTo(tol) > 0);
        out.add(total);
        return out;
    }

    @Override
    public List<Map<String, Object>> depreciationDetails(String periodTag) {
        if (!hasText(periodTag)) {
            periodTag = YearMonth.now().toString().replace("-", "");
        }
        return reportDao.depreciationDetails(periodTag);
    }

    @Override
    public Map<String, Object> netAssetLedger(long current, long size, String categoryCode,
                                              String costCenter, String deprStatus) {
        long total = reportDao.netAssetLedgerCount(categoryCode, costCenter, deprStatus);
        List<Map<String, Object>> rows = reportDao.netAssetLedger(categoryCode, costCenter,
                deprStatus, (current - 1) * size, size);
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal accum = BigDecimal.ZERO;
        BigDecimal impairment = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            cost = cost.add(nz((BigDecimal) r.get("ASSET_COST")));
            accum = accum.add(nz((BigDecimal) r.get("ACCUM_DEPR")));
            impairment = impairment.add(nz((BigDecimal) r.get("IMPAIRMENT")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("records", rows);
        out.put("pageCost", cost.setScale(2, RoundingMode.HALF_UP));
        out.put("pageAccum", accum.setScale(2, RoundingMode.HALF_UP));
        out.put("pageImpairment", impairment.setScale(2, RoundingMode.HALF_UP));
        out.put("pageNet", cost.subtract(accum).subtract(impairment).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    // ================= 7.8.3 盘点报告 =================

    @Override
    public Map<String, Object> countReportList(long current, long size, String keyword) {
        LambdaQueryWrapper<EamCountPlan> qw = new LambdaQueryWrapper<EamCountPlan>()
                .eq(EamCountPlan::getDelFlag, "0")
                .orderByDesc(EamCountPlan::getCreateDate);
        if (hasText(keyword)) {
            qw.like(EamCountPlan::getCntNo, keyword);
        }
        Page<EamCountPlan> page = countPlanDao.selectPage(new Page<>(current, size), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EamCountPlan p : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("cntNo", p.getCntNo());
            row.put("countType", p.getCountType());
            row.put("baseDate", p.getBaseDate());
            row.put("status", p.getStatus());
            row.put("totalQty", p.getTotalQty());
            row.put("countedQty", p.getCountedQty());
            row.put("coverageRate", p.getCoverageRate());
            row.put("reportStatus", p.getReportStatus());
            row.put("reportReady", hasText(p.getReportSummary()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", page.getTotal());
        out.put("records", rows);
        return out;
    }

    @Override
    public Map<String, Object> countReportDetail(String planId) {
        EamCountPlan plan = countPlanDao.selectById(planId);
        if (plan == null || !"0".equals(plan.getDelFlag())) {
            throw new ServiceException(404, "盘点计划不存在：" + planId);
        }
        boolean reportReady = hasText(plan.getReportSummary());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("cntNo", plan.getCntNo());
        out.put("countType", plan.getCountType());
        out.put("baseDate", plan.getBaseDate());
        out.put("status", plan.getStatus());
        out.put("coverageRate", plan.getCoverageRate());
        out.put("reportReady", reportReady);
        out.put("reportSummary", plan.getReportSummary());
        out.put("reportStatus", plan.getReportStatus());
        if (!reportReady) {
            out.put("progress", Map.of("totalQty", plan.getTotalQty() == null ? 0 : plan.getTotalQty(),
                    "countedQty", plan.getCountedQty() == null ? 0 : plan.getCountedQty()));
            return out;   // 未结案：仅进度，不显示差异分析
        }
        List<Map<String, Object>> byType = reportDao.countDiffByType(planId);
        int diffTotal = 0;
        BigDecimal amtTotal = BigDecimal.ZERO;
        for (Map<String, Object> t : byType) {
            diffTotal += ((Number) t.get("CNT")).intValue();
            amtTotal = amtTotal.add(nz((BigDecimal) t.get("AMT")));
        }
        BigDecimal denom = plan.getTotalQty() == null || plan.getTotalQty() == 0
                ? BigDecimal.ONE : BigDecimal.valueOf(plan.getTotalQty());
        out.put("diffByType", byType);
        out.put("diffCount", diffTotal);
        out.put("diffAmount", amtTotal.setScale(2, RoundingMode.HALF_UP));
        out.put("diffRate", BigDecimal.valueOf(diffTotal).divide(denom, 4, RoundingMode.HALF_UP));
        out.put("topByCategory", reportDao.countDiffTop(planId, false));
        out.put("topByDept", reportDao.countDiffTop(planId, true));
        out.put("reasons", reportDao.countDiffReasons(planId));
        return out;
    }

    // ================= 导出（design D6） =================

    @Override
    public String exportChangeCsv(String fromDate, String toDate, String evtType,
                                  String categoryCode, String costCenter) {
        long total = reportDao.changeEventCount(fromDate, toDate, evtType, categoryCode, costCenter);
        assertExportLimit(total);
        List<Map<String, Object>> rows = reportDao.changeEvents(fromDate, toDate, evtType,
                categoryCode, costCenter, 0, total + 1);
        StringBuilder sb = csvHead("变动日期,变动类型,卡号,资产名称,分类,部门,成本中心,来源单号,变动金额,说明");
        for (Map<String, Object> r : rows) {
            csvRow(sb, r.get("EVT_DATE"), r.get("EVT_TYPE"), r.get("CARD_NO"), r.get("ASSET_NAME"),
                    r.get("CATEGORY_CODE"), r.get("DEPT_NAME"), r.get("COST_CENTER"),
                    r.get("SOURCE_NO"), r.get("AMOUNT_DELTA"), r.get("REMARK"));
        }
        return sb.toString();
    }

    @Override
    public String exportDepreciationCsv(String periodTag) {
        if (!hasText(periodTag)) {
            periodTag = YearMonth.now().toString().replace("-", "");
        }
        List<Map<String, Object>> rows = reportDao.depreciationDetails(periodTag);
        assertExportLimit(rows.size());
        StringBuilder sb = csvHead("卡号,资产名称,分类,成本中心,原值,期初净值,本期折旧,期末累计折旧,期末净值");
        for (Map<String, Object> r : rows) {
            csvRow(sb, r.get("CARD_NO"), r.get("ASSET_NAME"), r.get("CATEGORY_CODE"),
                    r.get("COST_CENTER"), r.get("ASSET_COST"), r.get("BEGIN_NBV"),
                    r.get("PERIOD_DEPR"), r.get("END_ACCUM_DEPR"), r.get("END_NBV"));
        }
        return sb.toString();
    }

    @Override
    public String exportLedgerCsv(String categoryCode, String costCenter, String deprStatus) {
        long total = reportDao.netAssetLedgerCount(categoryCode, costCenter, deprStatus);
        assertExportLimit(total);
        List<Map<String, Object>> rows = reportDao.netAssetLedger(categoryCode, costCenter,
                deprStatus, 0, total + 1);
        StringBuilder sb = csvHead("卡号,资产名称,分类,部门,成本中心,原值,累计折旧,减值,净值,使用年限,折旧状态,启用日");
        for (Map<String, Object> r : rows) {
            csvRow(sb, r.get("CARD_NO"), r.get("ASSET_NAME"), r.get("CATEGORY_CODE"),
                    r.get("DEPT_NAME"), r.get("COST_CENTER_CODE"), r.get("ASSET_COST"),
                    r.get("ACCUM_DEPR"), r.get("IMPAIRMENT"), r.get("NET_VALUE"),
                    r.get("LIFE_YEARS"), r.get("DEPR_STATUS"), r.get("EFFECTIVE_DATE"));
        }
        return sb.toString();
    }

    private void assertExportLimit(long total) {
        int max = 50000;
        try {
            max = sysParamService.getAmount("EAM_REPORT_EXPORT_MAX", new BigDecimal("50000")).intValue();
        } catch (Exception ignore) {
            // 参数缺失用默认 5 万
        }
        if (total > max) {
            throw new ServiceException(422, "导出行数 " + total + " 超过上限 " + max
                    + "，请缩小期间或筛选范围后重试");
        }
    }

    private StringBuilder csvHead(String header) {
        return new StringBuilder("\uFEFF").append(header).append("\r\n");   // BOM + 中文表头
    }

    private void csvRow(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = cells[i] == null ? "" : String.valueOf(cells[i]);
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            sb.append(v);
        }
        sb.append("\r\n");
    }

    private static String prevPeriod(String periodTag) {
        int y = Integer.parseInt(periodTag.substring(0, 4));
        int m = Integer.parseInt(periodTag.substring(4, 6));
        m--;
        if (m == 0) {
            m = 12;
            y--;
        }
        return String.format("%d%02d", y, m);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}

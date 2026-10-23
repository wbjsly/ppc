package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.CostSummaryDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.entity.mrp.CostSummary;
import com.erp.entity.mrp.MrpMo;
import com.erp.service.SysParamService;
import com.erp.service.fin.CostReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 期间成本报表（M8-10-3，change add-cost-accounting，design D3/D4）。
 * 纯只读聚合：cost_summary 期间行 × 工单物料 join；红标阈值 TOLERANCE_DEFAULT
 * 取 rate 形态（0.005，与 VAR_RATE 小数尺度同源——盘点差异率同款取数）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReportServiceImpl implements CostReportService {

    private final CostSummaryDao summaryDao;
    private final MrpMoDao moDao;
    private final SysParamService sysParamService;

    // ================================================================
    // 3.1 期间报表（summary + rows）
    // ================================================================

    @Override
    public Map<String, Object> report(String period, String productCode, String reportStatus) {
        requirePeriod(period);
        BigDecimal tolerance = sysParamService.getRate("TOLERANCE_DEFAULT", new BigDecimal("0.005"));
        List<CostSummary> all = summaryDao.selectList(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getPeriod, period)
                .eq(CostSummary::getDelFlag, "0")
                .orderByDesc(CostSummary::getVarRate));
        Map<String, MrpMo> moMap = moMap(all);

        // 明细行（productCode 在 join 后过滤——summary 表无物料列）
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CostSummary s : all) {
            MrpMo mo = moMap.get(s.getMoId());
            String pCode = mo == null ? null : mo.getProductCode();
            if (productCode != null && !productCode.isEmpty()
                    && (pCode == null || !pCode.equals(productCode))) {
                continue;
            }
            if (reportStatus != null && !reportStatus.isEmpty()
                    && !reportStatus.equals(s.getReportStatus())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moId", s.getMoId());
            row.put("moNo", s.getMoNo());
            row.put("period", s.getPeriod());
            row.put("productCode", pCode);
            row.put("productName", mo == null ? null : mo.getProductName());
            row.put("materialCost", s.getMaterialCost());
            row.put("laborCost", s.getLaborCost());
            row.put("overheadCost", s.getOverheadCost());
            row.put("totalCost", s.getTotalCost());
            row.put("stdTotalCost", s.getStdTotalCost());
            row.put("varTotal", s.getVarTotal());
            row.put("varRate", s.getVarRate());
            row.put("unitCost", s.getUnitCost());
            row.put("goodQty", s.getGoodQty());
            row.put("reportStatus", s.getReportStatus());
            // 超容差红标（spec：VAR_RATE > TOLERANCE_DEFAULT 同口径 C-0-02）
            row.put("overTolerance", s.getVarRate() != null
                    && s.getVarRate().compareTo(tolerance) > 0);
            rows.add(row);
        }

        // 汇总卡（同一批筛选后行聚合——spec「汇总=明细合计」由同源保证）
        Map<String, Object> summary = aggregate(rows, tolerance);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("tolerance", tolerance);
        out.put("summary", summary);
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> aggregate(List<Map<String, Object>> rows, BigDecimal tolerance) {
        BigDecimal mat = BigDecimal.ZERO;
        BigDecimal lab = BigDecimal.ZERO;
        BigDecimal oh = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal std = BigDecimal.ZERO;
        BigDecimal varTotal = BigDecimal.ZERO;
        BigDecimal goodQty = BigDecimal.ZERO;
        int noQty = 0;
        int overCount = 0;
        int raised = 0;
        int reviewed = 0;
        for (Map<String, Object> r : rows) {
            mat = mat.add(nvl((BigDecimal) r.get("materialCost")));
            lab = lab.add(nvl((BigDecimal) r.get("laborCost")));
            oh = oh.add(nvl((BigDecimal) r.get("overheadCost")));
            total = total.add(nvl((BigDecimal) r.get("totalCost")));
            std = std.add(nvl((BigDecimal) r.get("stdTotalCost")));
            varTotal = varTotal.add(nvl((BigDecimal) r.get("varTotal")));
            BigDecimal q = (BigDecimal) r.get("goodQty");
            if (q == null || q.signum() == 0) {
                noQty++;
            } else {
                goodQty = goodQty.add(q);
            }
            if (Boolean.TRUE.equals(r.get("overTolerance"))) {
                overCount++;
            }
            if ("RAISED".equals(r.get("reportStatus"))) {
                raised++;
            }
            if ("REVIEWED".equals(r.get("reportStatus"))) {
                reviewed++;
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("moCount", rows.size());
        s.put("materialCost", mat);
        s.put("laborCost", lab);
        s.put("overheadCost", oh);
        s.put("totalCost", total);
        s.put("stdTotalCost", std);
        s.put("varTotal", varTotal);
        s.put("totalVarRate", std.signum() == 0 ? BigDecimal.ZERO
                : varTotal.abs().divide(std, 4, RoundingMode.HALF_UP));
        s.put("goodQty", goodQty);
        // 加权单位成本（GOOD_QTY 为 0/NULL 的工单剔除分母，单独计数展示）
        s.put("weightedUnitCost", goodQty.signum() == 0 ? null
                : total.divide(goodQty, 2, RoundingMode.HALF_UP));
        s.put("noQtyCount", noQty);
        s.put("overToleranceCount", overCount);
        s.put("raisedCount", raised);
        s.put("reviewedCount", reviewed);
        s.put("tolerance", tolerance);
        return s;
    }

    // ================================================================
    // 3.2 CSV（与 report 同源）+ trend
    // ================================================================

    @Override
    public String csv(String period, String productCode, String reportStatus) {
        Map<String, Object> rep = report(period, productCode, reportStatus);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rep.get("rows");
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM（Excel 中文列）
        sb.append("工单号,物料编码,物料名称,直接材料,直接人工,制造费用,总成本,标准总成本,")
                .append("总差异,差异率,单位成本,合格产量,差异报告状态,超容差\n");
        for (Map<String, Object> r : rows) {
            sb.append(csvCell(r.get("moNo"))).append(',')
                    .append(csvCell(r.get("productCode"))).append(',')
                    .append(csvCell(r.get("productName"))).append(',')
                    .append(csvCell(r.get("materialCost"))).append(',')
                    .append(csvCell(r.get("laborCost"))).append(',')
                    .append(csvCell(r.get("overheadCost"))).append(',')
                    .append(csvCell(r.get("totalCost"))).append(',')
                    .append(csvCell(r.get("stdTotalCost"))).append(',')
                    .append(csvCell(r.get("varTotal"))).append(',')
                    .append(csvCell(r.get("varRate"))).append(',')
                    .append(csvCell(r.get("unitCost"))).append(',')
                    .append(csvCell(r.get("goodQty"))).append(',')
                    .append(csvCell(r.get("reportStatus"))).append(',')
                    .append(Boolean.TRUE.equals(r.get("overTolerance")) ? "是" : "否")
                    .append('\n');
        }
        return sb.toString();
    }

    @Override
    public List<Map<String, Object>> trend() {
        // 按期间 GROUP BY（cost_summary.PERIOD 已持久化——design D4 否决快照表）
        List<CostSummary> all = summaryDao.selectList(new LambdaQueryWrapper<CostSummary>()
                .eq(CostSummary::getDelFlag, "0")
                .orderByAsc(CostSummary::getPeriod));
        Map<String, Map<String, Object>> byPeriod = new LinkedHashMap<>();
        for (CostSummary s : all) {
            Map<String, Object> g = byPeriod.computeIfAbsent(s.getPeriod(), p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("period", p);
                m.put("moCount", 0);
                m.put("materialCost", BigDecimal.ZERO);
                m.put("laborCost", BigDecimal.ZERO);
                m.put("overheadCost", BigDecimal.ZERO);
                m.put("totalCost", BigDecimal.ZERO);
                return m;
            });
            g.put("moCount", (Integer) g.get("moCount") + 1);
            g.put("materialCost", ((BigDecimal) g.get("materialCost")).add(nvl(s.getMaterialCost())));
            g.put("laborCost", ((BigDecimal) g.get("laborCost")).add(nvl(s.getLaborCost())));
            g.put("overheadCost", ((BigDecimal) g.get("overheadCost")).add(nvl(s.getOverheadCost())));
            g.put("totalCost", ((BigDecimal) g.get("totalCost")).add(nvl(s.getTotalCost())));
        }
        return new ArrayList<>(byPeriod.values());
    }

    // ================================================================
    // 私有助手
    // ================================================================

    private void requirePeriod(String period) {
        if (period == null || !period.matches("\\d{6}")) {
            throw new com.erp.common.ServiceException(422, "期间格式须为 yyyyMM");
        }
    }

    private Map<String, MrpMo> moMap(List<CostSummary> summaries) {
        List<String> ids = summaries.stream().map(CostSummary::getMoId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MrpMo> mos = moDao.selectBatchIds(ids);
        return mos.stream().collect(Collectors.toMap(MrpMo::getId, Function.identity(), (a, b) -> a));
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

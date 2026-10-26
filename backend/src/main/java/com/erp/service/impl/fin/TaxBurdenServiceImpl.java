package com.erp.service.impl.fin;

import com.erp.service.SysParamService;
import com.erp.service.fin.TaxBurdenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 税负率月度监控实现（实时计算，design D3：复用 tax-filing 汇总口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxBurdenServiceImpl implements TaxBurdenService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParamService;

    @Override
    public Map<String, Object> monthly(String periodEnd, int months) {
        if (months <= 0) {
            months = 6;
        }
        BigDecimal floor = decParam("TAX_BURDEN_FLOOR", new BigDecimal("0.01"));
        BigDecimal momLimit = decParam("TAX_BURDEN_MOM_LIMIT", new BigDecimal("0.30"));

        List<Map<String, Object>> rows = new ArrayList<>();
        YearMonth end = YearMonth.parse(periodEnd.substring(0, 4) + "-" + periodEnd.substring(4, 6));
        YearMonth cursor = end.minusMonths(months - 1);
        BigDecimal prevBurden = null;
        for (int i = 0; i < months; i++) {
            String p = cursor.toString().replace("-", "");
            Map<String, Object> row = computeRow(p, floor, momLimit, prevBurden);
            rows.add(row);
            Object b = row.get("vatBurden");
            prevBurden = b == null ? null : new BigDecimal(String.valueOf(b));
            cursor = cursor.plusMonths(1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("floor", floor);
        out.put("momLimit", momLimit);
        return out;
    }

    @Override
    public List<Map<String, Object>> signals(String periodEnd, int months) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) monthly(periodEnd, months).get("rows")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> breaches = (List<Map<String, Object>>) row.get("breaches");
            if (breaches != null && !breaches.isEmpty()) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("period", row.get("period"));
                sig.put("vatBurden", row.get("vatBurden"));
                sig.put("breaches", breaches);
                sig.put("level", "L4");
                sig.put("source", "tax-risk-monitoring");
                sig.put("suggestedAction", "复核税负率合理性（FR-4.14-5-5）：核对进销项与行业差异原因");
                out.add(sig);
            }
        }
        return out;
    }

    // ================================================================
    // 内部
    // ================================================================

    private Map<String, Object> computeRow(String period, BigDecimal floor, BigDecimal momLimit,
                                           BigDecimal prevBurden) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period);
        // 增值税侧：台账月度聚合（口径同 tax-filing summarize）
        BigDecimal outputTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_sales_invoice "
                        + "WHERE DEL_FLAG='0' AND STATUS IN ('ISSUED','CONFIRMED') "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", BigDecimal.class, period));
        BigDecimal inputTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND CERT_STATUS IN ('CERTIFIED','NOT_DEDUCTIBLE') "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", BigDecimal.class, period));
        BigDecimal transferOut = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TRANSFER_OUT_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?",
                BigDecimal.class, period));
        BigDecimal sales = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT - TAX_AMOUNT),0) FROM erp_fin_sales_invoice "
                        + "WHERE DEL_FLAG='0' AND STATUS IN ('ISSUED','CONFIRMED') "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", BigDecimal.class, period));
        row.put("outputTax", strip(outputTax));
        row.put("inputTax", strip(inputTax));
        row.put("transferOut", strip(transferOut));
        row.put("netSales", strip(sales));

        List<Map<String, Object>> breaches = new ArrayList<>();
        BigDecimal vatBurden = null;
        if (sales.signum() > 0) {
            BigDecimal vat = outputTax.subtract(inputTax).add(transferOut);
            vatBurden = vat.divide(sales, 6, RoundingMode.HALF_UP);
            row.put("vatBurden", vatBurden.setScale(4, RoundingMode.HALF_UP).toPlainString());
            if (vatBurden.compareTo(floor) < 0) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", "BELOW_FLOOR");
                b.put("message", "增值税税负率 " + percent(vatBurden) + " 低于下限 " + percent(floor));
                breaches.add(b);
            }
            if (prevBurden != null && prevBurden.signum() > 0) {
                BigDecimal drop = prevBurden.subtract(vatBurden).divide(prevBurden, 6, RoundingMode.HALF_UP);
                row.put("momChange", drop.setScale(4, RoundingMode.HALF_UP).negate().toPlainString());
                if (drop.compareTo(momLimit) > 0) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("type", "MOM_SWING");
                    b.put("message", "环比降幅 " + percent(drop) + " 超上限 " + percent(momLimit)
                            + "（上期 " + percent(prevBurden) + "）");
                    breaches.add(b);
                }
            }
        } else {
            row.put("vatBurden", null);
            row.put("applicable", "不适用（无不含税销售额）");
        }
        // 所得税侧：当年累计利润/收入（6xxx 聚合，CR−DR 统一口径）
        String y = period.substring(0, 4);
        BigDecimal profit = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN ACCOUNT_CODE IN ('6001','6301') "
                        + "THEN CR_TOTAL - DR_TOTAL ELSE DR_TOTAL - CR_TOTAL END),0) "
                        + "FROM erp_fin_balance WHERE PERIOD BETWEEN ? AND ? AND DEL_FLAG='0' "
                        + "AND ACCOUNT_CODE LIKE '6%'", BigDecimal.class, y + "01", period));
        BigDecimal revenue = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(CR_TOTAL - DR_TOTAL),0) FROM erp_fin_balance "
                        + "WHERE PERIOD BETWEEN ? AND ? AND DEL_FLAG='0' AND ACCOUNT_CODE = '6001'",
                BigDecimal.class, y + "01", period));
        row.put("profit", strip(profit));
        row.put("revenue", strip(revenue));
        if (revenue.signum() > 0) {
            row.put("citContribution", profit.divide(revenue, 4, RoundingMode.HALF_UP).toPlainString());
        } else {
            row.put("citContribution", null);
        }
        row.put("breaches", breaches);
        return row;
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private BigDecimal decParam(String key, BigDecimal dflt) {
        String v = sysParamService.getValue(key);
        try {
            return v == null || v.isBlank() ? dflt : new BigDecimal(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }
}

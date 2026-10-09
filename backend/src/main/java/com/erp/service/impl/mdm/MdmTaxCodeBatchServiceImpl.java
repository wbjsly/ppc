package com.erp.service.impl.mdm;

import com.erp.common.ServiceException;
import com.erp.common.TaxCodeRules;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.service.mdm.MdmTaxCodeBatchService;
import com.erp.service.mdm.MdmTaxCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 税码批量导入实现（design add-tax-code D5，沿 MdmExchangeRateBatchServiceImpl 模式）：
 * preview/batch 共用「归一化 + 分组排序 + 逐行判定」核心；batch 故意不加 @Transactional
 * —— 每行 create 的默认传播各自独立提交（BR-4.1-19 行级语义）。
 */
@Slf4j
@Service
public class MdmTaxCodeBatchServiceImpl implements MdmTaxCodeBatchService {

    private static final int MAX_BATCH = 500;

    private final MdmTaxCodeDao taxDao;
    private final MdmTaxCodeService taxService;

    public MdmTaxCodeBatchServiceImpl(MdmTaxCodeDao taxDao, MdmTaxCodeService taxService) {
        this.taxDao = taxDao;
        this.taxService = taxService;
    }

    // ---------- 预检（dry-run） ----------

    @Override
    public Map<String, Object> preview(List<Map<String, Object>> rows, String defaultPolicyNo) {
        List<Prepared> prepared = prepare(rows, defaultPolicyNo);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Prepared p : prepared) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowNo", p.rowNo);
            row.put("valid", p.reason == null);
            row.put("reason", p.reason);
            row.put("normalized", summary(p.tax));
            results.add(row);
        }
        long invalid = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("valid"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", results);
        result.put("total", results.size());
        result.put("invalid", invalid);
        return result;
    }

    // ---------- 按行独立提交 ----------

    @Override
    public Map<String, Object> batch(List<Map<String, Object>> rows, String defaultPolicyNo) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "导入行不能为空");
        }
        if (rows.size() > MAX_BATCH) {
            throw new ServiceException(422, "单批上限 " + MAX_BATCH + " 行，当前 " + rows.size()
                    + " 行，请拆批执行");
        }
        List<Prepared> prepared = prepare(rows, defaultPolicyNo);
        List<Map<String, Object>> details = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (Prepared p : prepared) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rowNo", p.rowNo);
            d.put("taxCode", p.tax == null ? "-" : p.tax.getTaxCode());
            if (p.reason != null) {
                d.put("result", "FAILED");
                d.put("reason", p.reason);
                failed++;
            } else {
                try {
                    // 每行独立事务（本方法无外层事务，create 的 @Transactional 默认传播即行级提交）
                    taxService.create(p.tax);
                    d.put("result", "SUCCESS");
                    d.put("reason", null);
                    succeeded++;
                } catch (ServiceException e) {
                    d.put("result", "FAILED");
                    d.put("reason", "[" + e.getCode() + "] " + e.getMessage());
                    failed++;
                } catch (Exception e) {
                    d.put("result", "FAILED");
                    d.put("reason", "系统异常：" + e.getMessage());
                    failed++;
                }
            }
            details.add(d);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", prepared.size());
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("details", details);
        log.info("tax-code batch done: total={} ok={} fail={}", prepared.size(), succeeded, failed);
        return result;
    }

    // ---------- 共用核心：归一化 + 分组排序 + 逐行判定 ----------

    private record Prepared(int rowNo, MdmTaxCode tax, String reason) {
    }

    /**
     * ① 字段归一化与公共文号/缺省枚举回填 → ② 字段规则 → ③ 按税码分组按生效日排序
     * 逐段判定（存量链 ∪ 批内前序 accepted 段，IntervalRules 单点）。
     */
    private List<Prepared> prepare(List<Map<String, Object>> rows, String defaultPolicyNo) {
        List<Prepared> raw = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int rowNo = intOf(row.get("rowNo"), 0);
            MdmTaxCode tax = mapRow(row, defaultPolicyNo);
            if (tax == null) {
                raw.add(new Prepared(rowNo, null, "行解析失败（列缺失或类型非法）"));
                continue;
            }
            String reason = TaxCodeRules.validateFields(tax);
            raw.add(new Prepared(rowNo, tax, reason));
        }
        Map<String, List<Prepared>> groups = raw.stream()
                .filter(p -> p.reason() == null)
                .collect(Collectors.groupingBy(
                        p -> p.tax().getTaxCode(),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        for (List<Prepared> group : groups.values()) {
            group.sort(Comparator.comparing(p -> p.tax().getEffectiveDate()));
            List<MdmTaxCode> accepted = new ArrayList<>(
                    taxDao.selectSequence(group.get(0).tax().getTaxCode(), null));
            List<Prepared> decided = new ArrayList<>();
            for (Prepared p : group) {
                var segs = accepted.stream()
                        .map(t -> new com.erp.common.IntervalRules.IntervalSeg(
                                t.getEffectiveDate(), t.getExpireDate()))
                        .toList();
                String gap = com.erp.common.IntervalRules.check(segs,
                        p.tax().getEffectiveDate(), p.tax().getExpireDate());
                decided.add(gap == null
                        ? new Prepared(p.rowNo(), p.tax(), null)
                        : new Prepared(p.rowNo(), p.tax(), gap));
                if (gap == null) {
                    accepted.add(p.tax()); // 前序段进入链，供后续行判定
                }
            }
            // 回填到原序（按 rowNo 保持用户输入顺序输出）
            for (Prepared d : decided) {
                for (int i = 0; i < raw.size(); i++) {
                    if (raw.get(i).rowNo() == d.rowNo() && raw.get(i).reason() == null) {
                        raw.set(i, d);
                        break;
                    }
                }
            }
        }
        return raw;
    }

    private MdmTaxCode mapRow(Map<String, Object> row, String defaultPolicyNo) {
        try {
            MdmTaxCode t = new MdmTaxCode();
            t.setTaxCode(str(row.get("taxCode")) == null ? null
                    : str(row.get("taxCode")).trim().toUpperCase());
            t.setEffectiveDate(date(str(row.get("effectiveDate"))));
            t.setExpireDate(date(str(row.get("expireDate"))));
            Object rateVal = row.get("taxRate");
            if (rateVal != null && !String.valueOf(rateVal).isBlank()) {
                t.setTaxRate(new BigDecimal(String.valueOf(rateVal).trim())
                        .setScale(TaxCodeRules.RATE_SCALE, RoundingMode.HALF_UP));
            }
            t.setScope(str(row.get("scope")) == null ? null : str(row.get("scope")).trim().toUpperCase());
            // 公共政策文号回填（S-4.1-07 一份政策覆盖多行）
            String policy = str(row.get("policyNo"));
            t.setPolicyNo(TaxCodeRules.isNotBlank(policy) ? policy.trim() : defaultPolicyNo);
            // 计税方式/税率类型缺省回填（spec：缺省 GENERAL/STANDARD）
            String calc = str(row.get("calcType"));
            t.setCalcType(TaxCodeRules.isNotBlank(calc) ? calc.trim().toUpperCase() : "GENERAL");
            String kind = str(row.get("rateKind"));
            t.setRateKind(TaxCodeRules.isNotBlank(kind) ? kind.trim().toUpperCase() : "STANDARD");
            return t;
        } catch (NumberFormatException | DateTimeParseException e) {
            return null; // 数字/日期解析失败 → 行级失败
        }
    }

    private String summary(MdmTaxCode t) {
        if (t == null) {
            return null;
        }
        return t.getTaxCode() + " " + t.getTaxRate() + "%"
                + " [" + t.getEffectiveDate() + ", " + t.getExpireDate() + "]"
                + (t.getScope() == null ? "" : " " + t.getScope());
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int intOf(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDate.parse(s.trim());
    }
}

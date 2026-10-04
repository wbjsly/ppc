package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ExchangeRateRules;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmExchangeRateDao;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.service.mdm.MdmExchangeRateBatchService;
import com.erp.service.mdm.MdmExchangeRateService;
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
 * 汇率批量更新实现（design add-exchange-rate-batch D2）：
 * preview/batch 共用同一「分组排序 + 逐行规则判定」核心，保证两阶段结果一致；
 * batch 故意不加 @Transactional —— 每行 create 的默认传播各自独立提交（按行语义）。
 */
@Slf4j
@Service
public class MdmExchangeRateBatchServiceImpl implements MdmExchangeRateBatchService {

    /** 批次上限（design D6，前后端双校验） */
    private static final int MAX_BATCH = 500;
    private static final int RATE_SCALE = 6;

    private final MdmExchangeRateDao rateDao;
    private final MdmExchangeRateService rateService;

    public MdmExchangeRateBatchServiceImpl(MdmExchangeRateDao rateDao,
                                           MdmExchangeRateService rateService) {
        this.rateDao = rateDao;
        this.rateService = rateService;
    }

    // ---------- 预检（dry-run） ----------

    @Override
    public Map<String, Object> preview(List<Map<String, Object>> rows, String defaultSourceFileNo) {
        List<Prepared> prepared = prepare(rows, defaultSourceFileNo);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Prepared p : prepared) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowNo", p.rowNo);
            row.put("valid", p.reason == null);
            row.put("reason", p.reason);
            row.put("normalized", p.reason == null ? summary(p.rate)
                    : (p.rate == null ? null : summary(p.rate)));
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
    public Map<String, Object> batch(List<Map<String, Object>> rows, String defaultSourceFileNo) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "导入行不能为空");
        }
        if (rows.size() > MAX_BATCH) {
            throw new ServiceException(422, "单批上限 " + MAX_BATCH + " 行，当前 " + rows.size()
                    + " 行，请拆批执行");
        }
        List<Prepared> prepared = prepare(rows, defaultSourceFileNo);
        List<Map<String, Object>> details = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        // 提交序 = 预检排序序（D6：批内衔接依赖顺序，两阶段一致）
        for (Prepared p : prepared) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rowNo", p.rowNo);
            d.put("baseQuote", p.rate == null ? "-"
                    : p.rate.getBaseCcy() + "/" + p.rate.getQuoteCcy() + " " + p.rate.getRateType());
            if (p.reason != null) {
                d.put("result", "FAILED");
                d.put("reason", p.reason);
                failed++;
            } else {
                try {
                    // 每行独立事务（本方法无外层事务，create 的 @Transactional 默认传播即行级提交）
                    rateService.create(buildRate(p.rate));
                    d.put("result", "SUCCESS");
                    d.put("reason", null);
                    succeeded++;
                } catch (ServiceException e) {
                    // 含幂等键重复 409（重放已成功行）等运行期失败
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
        log.info("exchange-rate batch done: total={} ok={} fail={}", prepared.size(), succeeded, failed);
        return result;
    }

    // ---------- 共用核心：归一化 + 分组排序 + 逐行判定 ----------

    private record Prepared(int rowNo, MdmExchangeRate rate, String reason) {
    }

    /**
     * ① 字段归一化与公共来源编号回填 → ② 字段规则 → ③ 按（币对×类型）分组
     * 按生效日排序逐段判定（存量序列 ∪ 批内前序 accepted 段）。
     * 字段不合法的行 rate 保留用于展示但不参与衔接链。
     */
    private List<Prepared> prepare(List<Map<String, Object>> rows, String defaultSourceFileNo) {
        List<Prepared> raw = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int rowNo = intOf(row.get("rowNo"), 0);
            MdmExchangeRate rate = mapRow(row, defaultSourceFileNo);
            if (rate == null) {
                raw.add(new Prepared(rowNo, null, "行解析失败（列缺失或类型非法）"));
                continue;
            }
            String reason = ExchangeRateRules.validateFields(rate);
            raw.add(new Prepared(rowNo, rate, reason));
        }
        // 按序列分组排序判定衔接（仅字段合法行参与链）
        Map<String, List<Prepared>> groups = raw.stream()
                .filter(p -> p.reason() == null)
                .collect(Collectors.groupingBy(
                        p -> key(p.rate()),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        for (List<Prepared> group : groups.values()) {
            group.sort(Comparator.comparing(p -> p.rate().getEffectiveDate()));
            List<MdmExchangeRate> accepted = new ArrayList<>(
                    rateDao.selectSequence(
                            group.get(0).rate().getBaseCcy(),
                            group.get(0).rate().getQuoteCcy(),
                            group.get(0).rate().getRateType(),
                            null));
            List<Prepared> decided = new ArrayList<>();
            for (Prepared p : group) {
                String gap = ExchangeRateRules.checkInterval(accepted,
                        p.rate().getEffectiveDate(), p.rate().getExpireDate());
                Prepared finalP = gap == null
                        ? new Prepared(p.rowNo(), p.rate(), null)
                        : new Prepared(p.rowNo(), p.rate(), gap);
                decided.add(finalP);
                if (gap == null) {
                    accepted.add(p.rate()); // 前序段进入链，供后续行判定
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

    private String key(MdmExchangeRate r) {
        return r.getBaseCcy() + "/" + r.getQuoteCcy() + ":" + r.getRateType();
    }

    private MdmExchangeRate mapRow(Map<String, Object> row, String defaultSourceFileNo) {
        try {
            MdmExchangeRate r = new MdmExchangeRate();
            r.setBaseCcy(str(row.get("baseCcy")));
            r.setQuoteCcy(str(row.get("quoteCcy")));
            r.setRateType(str(row.get("rateType")));
            if (r.getRateType() != null) {
                r.setRateType(r.getRateType().trim().toUpperCase());
            }
            r.setEffectiveDate(date(str(row.get("effectiveDate"))));
            r.setExpireDate(date(str(row.get("expireDate"))));
            Object rateVal = row.get("rate");
            if (rateVal != null && !String.valueOf(rateVal).isBlank()) {
                r.setRate(new BigDecimal(String.valueOf(rateVal).trim())
                        .setScale(RATE_SCALE, RoundingMode.HALF_UP));
            }
            String src = str(row.get("sourceFileNo"));
            // 公共来源编号回填（行 552 一次公告覆盖多行）
            r.setSourceFileNo(ExchangeRateRules.isNotBlank(src) ? src.trim() : defaultSourceFileNo);
            return r;
        } catch (NumberFormatException | DateTimeParseException e) {
            return null; // 数字/日期解析失败 → 行级失败
        }
    }

    private String summary(MdmExchangeRate r) {
        return r.getBaseCcy() + "/" + r.getQuoteCcy() + " " + r.getRateType()
                + " [" + r.getEffectiveDate() + ", " + r.getExpireDate() + "] " + r.getRate();
    }

    private MdmExchangeRate buildRate(MdmExchangeRate p) {
        // create 内部会再次校验（权威双保险）与精度归一
        return p;
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

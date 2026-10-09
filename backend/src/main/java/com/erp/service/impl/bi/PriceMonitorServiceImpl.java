package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.AgreementPriceDao;
import com.erp.dao.bi.BiConfigDao;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.dao.bi.QuoteAnomalyDao;
import com.erp.entity.bi.BiConfig;
import com.erp.entity.bi.BiPriceAlert;
import com.erp.security.IntfGuard;
import com.erp.service.bi.PriceMonitorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格监测工作台（spec price-monitoring）。
 * 异动处置状态机（已处置不可被补算/重复处置覆盖）；阈值在线调整留痕；
 * 协议价偏离与比价异常均为只读清单，不改变价控执行行为。
 */
@Slf4j
@Service
public class PriceMonitorServiceImpl implements PriceMonitorService {

    private final BiPriceAlertDao alertDao;
    private final BiConfigDao configDao;
    private final AgreementPriceDao agreementPriceDao;
    private final QuoteAnomalyDao quoteAnomalyDao;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public PriceMonitorServiceImpl(BiPriceAlertDao alertDao, BiConfigDao configDao,
                                   AgreementPriceDao agreementPriceDao,
                                   QuoteAnomalyDao quoteAnomalyDao) {
        this.alertDao = alertDao;
        this.configDao = configDao;
        this.agreementPriceDao = agreementPriceDao;
        this.quoteAnomalyDao = quoteAnomalyDao;
    }

    @Override
    public Map<String, Object> alertList(String monthTag, String status, String itemCode) {
        List<BiPriceAlert> rows = alertDao.selectForManage(
                monthTag == null || monthTag.isEmpty() ? null : monthTag,
                status == null || status.isEmpty() ? null : status,
                itemCode == null || itemCode.isEmpty() ? null : itemCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("open", rows.stream().filter(a -> BiPriceAlert.ST_OPEN.equals(a.getStatus())).count());
        return out;
    }

    @Override
    public Map<String, Object> handle(String alertId, String action, String note) {
        BiPriceAlert a = alertDao.selectById(alertId);
        if (a == null) {
            throw new ServiceException(404, "异动记录不存在");
        }
        if (!BiPriceAlert.ST_OPEN.equals(a.getStatus())) {
            // 已处置不可重复处置、不可篡改留痕
            throw new ServiceException(422, "异动记录已处置（" + a.getStatus() + "），处置信息不可修改");
        }
        if (!"HANDLED".equalsIgnoreCase(action) && !"IGNORED".equalsIgnoreCase(action)) {
            throw new ServiceException(400, "action 仅支持 HANDLED / IGNORED");
        }
        if ("HANDLED".equalsIgnoreCase(action) && (note == null || note.trim().length() < 2)) {
            throw new ServiceException(400, "处置须填写备注（不少于 2 字）");
        }
        a.setStatus("HANDLED".equalsIgnoreCase(action)
                ? BiPriceAlert.ST_HANDLED : BiPriceAlert.ST_IGNORED);
        a.setHandleBy(IntfGuard.currentUser());
        a.setHandleAt(LocalDateTime.now());
        a.setHandleNote(note);
        alertDao.updateById(a);
        return Map.of("id", a.getId(), "status", a.getStatus(), "handleBy", a.getHandleBy(),
                "handleAt", a.getHandleAt());
    }

    // ---------------------------------------------------------------- 阈值在线配置

    @Override
    public Map<String, Object> threshold(String key, String fallback) {
        BiConfig c = configDao.selectByKey(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", c == null ? fallback : c.getConfigValue());
        out.put("remark", c == null ? null : c.getRemark());
        out.put("history", parseQuietly(c == null ? "[]" : c.getHistoryJson()));
        out.put("source", c == null ? "DEFAULT" : "ONLINE");
        return out;
    }

    @Override
    public BigDecimal thresholdValue(String key, BigDecimal fallback) {
        BiConfig c = configDao.selectByKey(key);
        if (c == null) {
            return fallback;
        }
        try {
            return new BigDecimal(c.getConfigValue().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public Map<String, Object> setThreshold(String key, String value, String remark) {
        if (value == null || value.trim().isEmpty()) {
            throw new ServiceException(400, "value 必填");
        }
        try {
            new BigDecimal(value.trim());
        } catch (Exception e) {
            throw new ServiceException(400, "value 必须为数值");
        }
        BiConfig c = configDao.selectByKey(key);
        String oldValue = c == null ? null : c.getConfigValue();
        List<Map<String, Object>> history = c == null ? new ArrayList<>()
                : parseHistory(c.getHistoryJson());
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", oldValue);
        change.put("new", value.trim());
        change.put("by", IntfGuard.currentUser());
        change.put("at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        history.add(change);

        if (c == null) {
            c = new BiConfig();
            c.setConfigKey(key);
            c.setConfigValue(value.trim());
            c.setRemark(remark);
            c.setHistoryJson(toJson(history));
            configDao.insert(c);
        } else {
            c.setConfigValue(value.trim());
            c.setRemark(remark == null ? c.getRemark() : remark);
            c.setHistoryJson(toJson(history));
            configDao.updateById(c);
        }
        // 对调整后的新判定生效，已生成异动不重算（spec）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("value", value.trim());
        out.put("old", oldValue);
        out.put("by", change.get("by"));
        out.put("at", change.get("at"));
        out.put("note", "对后续判定生效，已生成异动不追溯重算");
        return out;
    }

    // ---------------------------------------------------------------- 协议价偏离

    @Override
    public Map<String, Object> deviationList() {
        BigDecimal tolerance = thresholdValue("PRICE_TOLERANCE_PCT", new BigDecimal("5"));
        List<Map<String, Object>> out = new ArrayList<>();
        // 取最近 3 个自然月快照的物料集合，与协议价比对
        java.time.YearMonth now = java.time.YearMonth.now();
        List<String> months = List.of(now.minusMonths(1).toString().replace("-", ""),
                now.minusMonths(2).toString().replace("-", ""),
                now.toString().replace("-", ""));
        for (String m : months) {
            for (Map<String, Object> snap : recentPrices(m)) {
                String item = str(snap.get("itemCode"));
                if (item == null) {
                    continue;
                }
                for (Map<String, Object> ag : agreementPriceDao.listByItem(item)) {
                    BigDecimal last = bd(snap.get("avgPrice"));
                    BigDecimal agPrice = bd(ag.get("unitPrice"));
                    if (agPrice.signum() == 0) {
                        continue;
                    }
                    BigDecimal dev = last.subtract(agPrice).divide(agPrice, 4,
                            java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("monthTag", m);
                    row.put("itemCode", item);
                    row.put("supplierId", snap.get("supplierId"));
                    row.put("lastPrice", last);
                    row.put("agreementPrice", agPrice);
                    row.put("agreementNo", ag.get("agreementNo"));
                    row.put("deviationPct", dev);
                    row.put("overTolerance", dev.compareTo(tolerance) > 0);
                    row.put("tolerancePct", tolerance);
                    row.put("priceControl", quoteAnomalyDao.latestPriceControl(item));
                    out.add(row);
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", out);
        r.put("overCount", out.stream().filter(x -> Boolean.TRUE.equals(x.get("overTolerance"))).count());
        r.put("tolerancePct", tolerance);
        r.put("readOnly", true);
        return r;
    }

    private List<Map<String, Object>> recentPrices(String monthTag) {
        return agreementPriceDao.pricesOfMonths(monthTag);
    }

    // ---------------------------------------------------------------- 比价异常

    @Override
    public Map<String, Object> quoteAnomalies() {
        List<Map<String, Object>> rows = quoteAnomalyDao.anomalies();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("count", rows.size());
        out.put("rule", "BR-4.2-12 报价偏离同 RFQ 有效报价均值 ±20%（只读集中呈现，跳转比价矩阵处置）");
        return out;
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseHistory(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseQuietly(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}

package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;
import com.erp.service.bi.MetricDictService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径字典实现（spec bi-metric-dictionary，design D4）。
 * - 要素校验：metricKey/name/definition/formula/timeDim/srcMapping 必填（400）
 * - 行级版本：变更时旧版 IS_CURRENT=0，新行 +1 版本
 * - diff（C-4.10-01）：公式 JSON 规范化后逐 key 字段级比对，差异 422 + 差异报告
 * - 反向扫描：公式变更 → USED_BY 引用方全部置 PENDING_UPDATE
 */
@Slf4j
@Service
public class MetricDictServiceImpl implements MetricDictService {

    private final BiMetricDictDao dictDao;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MetricDictServiceImpl(BiMetricDictDao dictDao) {
        this.dictDao = dictDao;
    }

    // ---------------------------------------------------------------- 登记

    @Override
    public Map<String, Object> register(Map<String, Object> p) {
        String metricKey = str(p.get("metricKey"));
        String name = str(p.get("name"));
        String definition = str(p.get("definition"));
        String formula = str(p.get("formula"));
        String timeDim = str(p.get("timeDim"));
        String srcMapping = str(p.get("srcMapping"));
        if (metricKey == null || name == null || definition == null || formula == null
                || timeDim == null || srcMapping == null) {
            List<String> missing = new ArrayList<>();
            if (metricKey == null) { missing.add("metricKey"); }
            if (name == null) { missing.add("name"); }
            if (definition == null) { missing.add("definition"); }
            if (formula == null) { missing.add("formula"); }
            if (timeDim == null) { missing.add("timeDim"); }
            if (srcMapping == null) { missing.add("srcMapping"); }
            throw new ServiceException(400, "口径要素缺失，拒绝登记：" + String.join("、", missing));
        }
        if (!isJson(formula) || !isJson(srcMapping)) {
            throw new ServiceException(400, "formula 与 srcMapping 必须为合法 JSON");
        }

        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        BiMetricDict row = new BiMetricDict();
        row.setMetricKey(metricKey);
        row.setName(name);
        row.setDefinition(definition);
        row.setFormula(formula);
        row.setDataPrecision(p.get("dataPrecision") == null ? 2 : Integer.parseInt(String.valueOf(p.get("dataPrecision"))));
        row.setTimeDim(timeDim);
        row.setFilterCond(str(p.get("filterCond")));
        row.setSrcMapping(srcMapping);
        row.setStatus(BiMetricDict.ST_DRAFT);
        row.setRemark(str(p.get("remark")));

        if (curr == null) {
            row.setVersion(1);
            row.setIsCurrent(true);
            row.setUsedBy("[]");
            dictDao.insert(row);
        } else {
            // 更新当前版本内容（未发布）或开新版本（已发布 → 走 changeFormula 语义）
            if (BiMetricDict.ST_PUBLISHED.equals(curr.getStatus())) {
                throw new ServiceException(422, "已发布指标请走公式变更入口（生成新版本并触发引用扫描）");
            }
            curr.setName(name);
            curr.setDefinition(definition);
            curr.setFormula(formula);
            curr.setDataPrecision(row.getDataPrecision());
            curr.setTimeDim(timeDim);
            curr.setFilterCond(row.getFilterCond());
            curr.setSrcMapping(srcMapping);
            curr.setRemark(row.getRemark());
            dictDao.updateById(curr);
            row = curr;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("metricKey", row.getMetricKey());
        out.put("version", row.getVersion());
        out.put("status", row.getStatus());
        return out;
    }

    // ---------------------------------------------------------------- 发布 + diff（C-4.10-01）

    @Override
    public Map<String, Object> publish(String metricKey, String submittedFormula) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        // 发布前二次校验：提交的计算逻辑须与字典公式逐 key 一致
        Map<String, Object> diff = doDiff(curr.getFormula(), submittedFormula);
        if (!Boolean.TRUE.equals(diff.get("consistent"))) {
            throw new ServiceException(422, "口径 diff 校验失败（C-4.10-01），差异报告：" + diff.get("report"));
        }
        curr.setStatus(BiMetricDict.ST_PUBLISHED);
        dictDao.updateById(curr);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", curr.getId());
        out.put("metricKey", curr.getMetricKey());
        out.put("status", curr.getStatus());
        out.put("version", curr.getVersion());
        return out;
    }

    @Override
    public Map<String, Object> diffCheck(String metricKey, String submittedFormula) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        return doDiff(curr.getFormula(), submittedFormula);
    }

    /** 字段级 diff：两份公式 JSON 规范化后逐 key 比对，收集差异路径 */
    private Map<String, Object> doDiff(String dictFormula, String submittedFormula) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> report = new ArrayList<>();
        if (submittedFormula == null || submittedFormula.trim().isEmpty()) {
            report.add(diffEntry("-", "submitted", "空公式"));
            out.put("consistent", false);
            out.put("report", report);
            return out;
        }
        try {
            JsonNode a = mapper.readTree(dictFormula == null ? "{}" : dictFormula);
            JsonNode b = mapper.readTree(submittedFormula);
            diffNode("", a, b, report);
        } catch (Exception e) {
            report.add(diffEntry("-", "parse", "公式 JSON 解析失败：" + e.getMessage()));
        }
        out.put("consistent", report.isEmpty());
        out.put("report", report);
        return out;
    }

    private void diffNode(String path, JsonNode a, JsonNode b, List<Map<String, Object>> report) {
        if (a == null || b == null) {
            if (a != b) {
                report.add(diffEntry(path, "presence", "字典=" + a + " 提交=" + b));
            }
            return;
        }
        if (a.isObject() && b.isObject()) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            Iterator<String> ia = a.fieldNames();
            while (ia.hasNext()) { keys.add(ia.next()); }
            Iterator<String> ib = b.fieldNames();
            while (ib.hasNext()) { keys.add(ib.next()); }
            for (String k : keys) {
                diffNode(path.isEmpty() ? k : path + "." + k, a.get(k), b.get(k), report);
            }
        } else if (!a.equals(b)) {
            report.add(diffEntry(path, "value", "字典=" + a + " 提交=" + b));
        }
    }

    private Map<String, Object> diffEntry(String path, String kind, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("kind", kind);
        m.put("detail", detail);
        return m;
    }

    // ---------------------------------------------------------------- 公式变更 + 反向扫描

    @Override
    public Map<String, Object> changeFormula(String metricKey, String newFormula, String reason) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        if (newFormula == null || !isJson(newFormula)) {
            throw new ServiceException(400, "newFormula 必须为合法 JSON");
        }
        int nextVer = (dictDao.selectMaxVersion(metricKey) == null ? 0 : dictDao.selectMaxVersion(metricKey)) + 1;

        // 1) 旧版本退位（历史保留）
        curr.setIsCurrent(false);
        dictDao.updateById(curr);

        // 2) 新版本行
        BiMetricDict fresh = new BiMetricDict();
        fresh.setMetricKey(metricKey);
        fresh.setName(curr.getName());
        fresh.setDefinition(curr.getDefinition());
        fresh.setFormula(newFormula);
        fresh.setDataPrecision(curr.getDataPrecision());
        fresh.setTimeDim(curr.getTimeDim());
        fresh.setFilterCond(curr.getFilterCond());
        fresh.setSrcMapping(curr.getSrcMapping());
        fresh.setVersion(nextVer);
        fresh.setIsCurrent(true);
        fresh.setStatus(BiMetricDict.ST_PUBLISHED);
        fresh.setUsedBy(curr.getUsedBy());
        fresh.setRemark(reason);
        dictDao.insert(fresh);

        // 3) 反向扫描：USED_BY 登记的引用方状态全部置 PENDING_UPDATE（spec Requirement）
        List<Map<String, Object>> consumers = parseConsumers(curr.getUsedBy());
        for (Map<String, Object> c : consumers) {
            c.put("status", "PENDING_UPDATE");
            log.info("metric formula changed, mark consumer PENDING_UPDATE: {} -> {}", metricKey, c.get("id"));
        }
        fresh.setUsedBy(toJson(consumers));
        fresh.setRemark((reason == null ? "" : reason + "；") + "反向扫描引用方 " + consumers.size() + " 个待更新");
        dictDao.updateById(fresh);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fresh.getId());
        out.put("metricKey", metricKey);
        out.put("version", nextVer);
        out.put("consumers", consumers);
        out.put("status", fresh.getStatus());
        return out;
    }

    @Override
    public void bindUsage(String metricKey, String consumerId) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        List<Map<String, Object>> consumers = parseConsumers(curr.getUsedBy());
        boolean exists = consumers.stream().anyMatch(c -> consumerId.equals(c.get("id")));
        if (!exists) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", consumerId);
            c.put("status", "ACTIVE");
            consumers.add(c);
            curr.setUsedBy(toJson(consumers));
            dictDao.updateById(curr);
        }
    }

    @Override
    public Map<String, Object> consumerStatus(String metricKey) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricKey", metricKey);
        out.put("version", curr.getVersion());
        out.put("consumers", parseConsumers(curr.getUsedBy()));
        out.put("pending", parseConsumers(curr.getUsedBy()).stream()
                .anyMatch(c -> "PENDING_UPDATE".equals(c.get("status"))));
        return out;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public Page<BiMetricDict> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<BiMetricDict> qw = new LambdaQueryWrapper<>();
        qw.eq(Boolean.TRUE.equals(true), BiMetricDict::getIsCurrent, true);
        qw.eq(status != null && !status.trim().isEmpty(), BiMetricDict::getStatus,
                status == null ? "" : status.trim());
        qw.like(keyword != null && !keyword.trim().isEmpty(), BiMetricDict::getName,
                keyword == null ? "" : keyword.trim());
        qw.orderByDesc(BiMetricDict::getCreateDate);
        return dictDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public List<BiMetricDictHistory> history(String metricKey) {
        return dictDao.selectHistory(metricKey);
    }

    @Override
    public BiMetricDict current(String metricKey) {
        return dictDao.selectCurrent(metricKey);
    }

    // ---------------------------------------------------------------- helpers

    /** USED_BY 解析：[{id,status}] 对象数组；兼容旧字符串数组 */
    private List<Map<String, Object>> parseConsumers(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            List<Object> raw = mapper.readValue(json, new TypeReference<List<Object>>() { });
            for (Object o : raw) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (o instanceof Map<?, ?> mm) {
                    m.put("id", String.valueOf(mm.get("id")));
                    m.put("status", mm.get("status") == null ? "ACTIVE" : String.valueOf(mm.get("status")));
                } else {
                    m.put("id", String.valueOf(o));
                    m.put("status", "ACTIVE");
                }
                out.add(m);
            }
        } catch (Exception e) {
            // 保持空表
        }
        return out;
    }

    private boolean isJson(String s) {
        try {
            mapper.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
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

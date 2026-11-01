package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.dao.bi.BiMetricLineageDao;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricLineage;
import com.erp.service.bi.MetricLineageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指标血缘实现（add-metric-dict-console design D4，spec bi-metric-lineage）。
 * 三来源采集幂等清插；快照只增审计；模板反查用 METRICS_JSON 精确解析（防 LIKE 误匹配）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricLineageServiceImpl implements MetricLineageService {

    /** 指标编码格式（spec 6.3.1：M-<域>-<序号>） */
    private static final Pattern METRIC_CODE = Pattern.compile("M-[A-Z]{2,8}-\\d{3}");
    /** srcMapping key 形如 表名.列名 */
    private static final Pattern TABLE_COL = Pattern.compile("^([A-Za-z0-9_]+)\\.");

    private final BiMetricDictDao dictDao;
    private final BiMetricLineageDao lineageDao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @Transactional
    public Map<String, Object> collectAndSnapshot(String metricKey, int lineageVersion) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        // 幂等：同版本快照清后插——物理 DELETE（@TableLogic 软删行仍占 UK
        // (METRIC_KEY,LINEAGE_VERSION,NODE_TYPE,NODE_ID)，重采会撞幽灵键）
        jdbc.update("DELETE FROM erp_bi_metric_lineage WHERE METRIC_KEY = ? AND LINEAGE_VERSION = ?",
                metricKey, lineageVersion);

        List<BiMetricLineage> rows = new ArrayList<>();
        rows.addAll(scanUpstreamTables(metricKey, curr.getSrcMapping(), lineageVersion));
        rows.addAll(scanDerivedMetrics(metricKey, curr.getFormula(), lineageVersion));
        rows.addAll(scanReferencingReports(metricKey, lineageVersion));
        LocalDateTime now = LocalDateTime.now();
        for (BiMetricLineage r : rows) {
            r.setSnapshotAt(now);
            r.setCreateBy("system");
            lineageDao.insert(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricKey", metricKey);
        out.put("version", lineageVersion);
        out.put("count", rows.size());
        return out;
    }

    @Override
    public Map<String, Object> graph(String metricKey) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        // 最新快照；无快照现算（不落库）
        List<BiMetricLineage> snaps = lineageDao.selectList(new LambdaQueryWrapper<BiMetricLineage>()
                .eq(BiMetricLineage::getMetricKey, metricKey)
                .orderByDesc(BiMetricLineage::getLineageVersion)
                .last("LIMIT 500"));
        if (snaps.isEmpty()) {
            return view(metricKey, curr.getVersion(), collectFresh(metricKey, curr), true);
        }
        int latest = snaps.get(0).getLineageVersion();
        List<BiMetricLineage> latestRows = new ArrayList<>();
        for (BiMetricLineage r : snaps) {
            if (r.getLineageVersion() == latest) {
                latestRows.add(r);
            }
        }
        return view(metricKey, latest, latestRows, false);
    }

    @Override
    public Map<String, Object> graphVersion(String metricKey, int version) {
        List<BiMetricLineage> rows = lineageDao.selectList(new LambdaQueryWrapper<BiMetricLineage>()
                .eq(BiMetricLineage::getMetricKey, metricKey)
                .eq(BiMetricLineage::getLineageVersion, version));
        if (rows.isEmpty()) {
            throw new ServiceException(404, "血缘快照不存在：" + metricKey + " v" + version);
        }
        return view(metricKey, version, rows, false);
    }

    @Override
    @Transactional
    public Map<String, Object> bind(String metricKey, String nodeType, String nodeId,
                                    String nodeName) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        if (!BiMetricLineage.NODE_METRIC.equals(nodeType)
                && !BiMetricLineage.NODE_TABLE.equals(nodeType)) {
            throw new ServiceException(422, "手工绑定仅支持 METRIC（衍生指标）/ TABLE（上游表）");
        }
        if (!StringUtils.hasText(nodeId)) {
            throw new ServiceException(422, "关联方标识必填");
        }
        // 幂等：同版本同节点跳过
        Long dup = lineageDao.selectCount(new LambdaQueryWrapper<BiMetricLineage>()
                .eq(BiMetricLineage::getMetricKey, metricKey)
                .eq(BiMetricLineage::getLineageVersion, curr.getVersion())
                .eq(BiMetricLineage::getNodeType, nodeType)
                .eq(BiMetricLineage::getNodeId, nodeId));
        if (dup != null && dup > 0) {
            return Map.of("id", "-", "duplicated", true);
        }
        BiMetricLineage r = new BiMetricLineage();
        r.setId(uuid());
        r.setMetricKey(metricKey);
        r.setLineageVersion(curr.getVersion());
        r.setNodeType(nodeType);
        r.setNodeId(nodeId);
        r.setNodeName(nodeName);
        r.setHighRisk("0");
        r.setSnapshotAt(LocalDateTime.now());
        r.setRemark("手工绑定");
        r.setCreateBy(operator());
        lineageDao.insert(r);
        return Map.of("id", r.getId(), "duplicated", false);
    }

    @Override
    public Map<String, Object> impactReport(String metricKey, int version) {
        List<BiMetricLineage> rows = lineageDao.selectList(new LambdaQueryWrapper<BiMetricLineage>()
                .eq(BiMetricLineage::getMetricKey, metricKey)
                .eq(BiMetricLineage::getLineageVersion, version));
        if (rows.isEmpty()) {
            throw new ServiceException(404, "血缘快照不存在：" + metricKey + " v" + version);
        }
        Map<String, Object> out = view(metricKey, version, rows, false);
        // 高风险报表清单置顶（S-4.10-15：驾驶舱核心/对外披露标注）
        List<Map<String, Object>> highRisk = new ArrayList<>();
        for (Map<String, Object> rep : (List<Map<String, Object>>) out.get("reports")) {
            if ("1".equals(String.valueOf(rep.get("highRisk")))) {
                highRisk.add(rep);
            }
        }
        out.put("highRiskReports", highRisk);
        out.put("summary", "上游表 " + ((List<?>) out.get("upstreamTables")).size()
                + "，衍生指标 " + ((List<?>) out.get("derivedMetrics")).size()
                + "，引用报表 " + ((List<?>) out.get("reports")).size()
                + "（高风险 " + highRisk.size() + "）");
        return out;
    }

    // ---------------------------------------------------------------- 三来源采集

    /** 上游表：srcMapping key `表名.列名` → 表名 */
    private List<BiMetricLineage> scanUpstreamTables(String metricKey, String srcMapping,
                                                     int version) {
        List<BiMetricLineage> out = new ArrayList<>();
        if (srcMapping == null || srcMapping.trim().isEmpty()) {
            return out;
        }
        try {
            Map<String, Object> map = mapper.readValue(srcMapping,
                    new TypeReference<Map<String, Object>>() { });
            for (String k : map.keySet()) {
                Matcher m = TABLE_COL.matcher(k.trim());
                if (m.find()) {
                    out.add(row(metricKey, version, BiMetricLineage.NODE_TABLE,
                            m.group(1), m.group(1), "0"));
                }
            }
        } catch (Exception e) {
            log.warn("lineage srcMapping parse failed for {}: {}", metricKey, e.getMessage());
        }
        return dedup(out);
    }

    /** 衍生指标：formula.expr 中匹配已注册指标编码（排除自身） */
    private List<BiMetricLineage> scanDerivedMetrics(String metricKey, String formula,
                                                     int version) {
        List<BiMetricLineage> out = new ArrayList<>();
        if (formula == null) {
            return out;
        }
        java.util.Set<String> registered = registeredKeys();
        Matcher m = METRIC_CODE.matcher(formula);
        while (m.find()) {
            String code = m.group();
            if (!code.equals(metricKey) && registered.contains(code)) {
                BiMetricDict dep = dictDao.selectCurrent(code);
                out.add(row(metricKey, version, BiMetricLineage.NODE_METRIC,
                        code, dep == null ? code : dep.getName(), "0"));
            }
        }
        return dedup(out);
    }

    /** 引用报表：模板 METRICS_JSON 精确解析（防 LIKE 误匹配），HIGH_RISK 按备注标记 */
    private List<BiMetricLineage> scanReferencingReports(String metricKey, int version) {
        List<BiMetricLineage> out = new ArrayList<>();
        List<Map<String, Object>> tpls = jdbc.queryForList(
                "SELECT ID, CODE, NAME, REMARK, METRICS_JSON "
                        + "FROM erp_bi_report_template WHERE DEL_FLAG = '0' AND METRICS_JSON LIKE ?",
                "%" + metricKey + "%");
        for (Map<String, Object> t : tpls) {
            if (!metricsJsonRefs(strVal(t.get("METRICS_JSON")), metricKey)) {
                continue; // LIKE 粗筛后精确解析排除误匹配
            }
            String remark = strVal(t.get("REMARK"));
            boolean highRisk = remark.contains("RISK:HIGH");
            out.add(row(metricKey, version, BiMetricLineage.NODE_REPORT,
                    strVal(t.get("ID")),
                    strVal(t.get("CODE")) + " " + strVal(t.get("NAME")),
                    highRisk ? "1" : "0"));
        }
        return out;
    }

    private static String strVal(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** METRICS_JSON 数组精确匹配 metricKey（防 M-SD-001 命中 M-SD-0012） */
    private boolean metricsJsonRefs(String json, String metricKey) {
        if (json == null || json.trim().isEmpty() || "null".equals(json)) {
            return false;
        }
        try {
            List<Map<String, Object>> arr = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> m : arr) {
                if (metricKey.equals(String.valueOf(m.get("metricKey")))) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 现算三来源（无快照时 graph 用，不落库） */
    private List<BiMetricLineage> collectFresh(String metricKey, BiMetricDict curr) {
        int v = curr.getVersion();
        List<BiMetricLineage> rows = new ArrayList<>();
        rows.addAll(scanUpstreamTables(metricKey, curr.getSrcMapping(), v));
        rows.addAll(scanDerivedMetrics(metricKey, curr.getFormula(), v));
        rows.addAll(scanReferencingReports(metricKey, v));
        return rows;
    }

    /** 快照结构化视图：三层清单 + 元信息 */
    private Map<String, Object> view(String metricKey, int version,
                                     List<BiMetricLineage> rows, boolean fresh) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricKey", metricKey);
        out.put("version", version);
        out.put("fresh", fresh);
        out.put("upstreamTables", nodes(rows, BiMetricLineage.NODE_TABLE));
        out.put("derivedMetrics", nodes(rows, BiMetricLineage.NODE_METRIC));
        out.put("reports", nodes(rows, BiMetricLineage.NODE_REPORT));
        return out;
    }

    private List<Map<String, Object>> nodes(List<BiMetricLineage> rows, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiMetricLineage r : rows) {
            if (type.equals(r.getNodeType())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", r.getNodeId());
                m.put("nodeName", r.getNodeName());
                m.put("highRisk", r.getHighRisk());
                out.add(m);
            }
        }
        return out;
    }

    private java.util.Set<String> registeredKeys() {
        List<BiMetricDict> all = dictDao.selectList(new LambdaQueryWrapper<BiMetricDict>()
                .select(BiMetricDict::getMetricKey));
        java.util.Set<String> out = new java.util.HashSet<>();
        for (BiMetricDict d : all) {
            out.add(d.getMetricKey());
        }
        return out;
    }

    private BiMetricLineage row(String metricKey, int version, String type,
                                String nodeId, String nodeName, String highRisk) {
        BiMetricLineage r = new BiMetricLineage();
        r.setId(uuid());
        r.setMetricKey(metricKey);
        r.setLineageVersion(version);
        r.setNodeType(type);
        r.setNodeId(nodeId);
        r.setNodeName(nodeName);
        r.setHighRisk(highRisk);
        return r;
    }

    private List<BiMetricLineage> dedup(List<BiMetricLineage> rows) {
        List<BiMetricLineage> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BiMetricLineage r : rows) {
            String k = r.getNodeType() + ":" + r.getNodeId();
            if (seen.add(k)) {
                out.add(r);
            }
        }
        return out;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String operator() {
        try {
            String u = com.erp.util.SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}

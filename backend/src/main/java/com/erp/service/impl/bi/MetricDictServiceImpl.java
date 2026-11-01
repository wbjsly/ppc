package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.MetricLineageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 口径字典实现（spec bi-metric-dictionary，add-metric-dict-console 增强）。
 * - 要素校验：六要素必填（400）+ 十二要素扩展（编码格式/公式类型/分子分母/维度/责任人，D3）
 * - 行级版本：变更时旧版 IS_CURRENT=0，新行 +1 版本
 * - diff（C-4.10-01）：公式 JSON 规范化后逐 key 字段级比对，差异 422 + 差异报告
 * - 变更两段式治理（D2）：申请 CHANGE_PENDING → 治理单签 → GRAYING（旧版仍生效）
 *   → 切换双审（C-4.10-08）→ IS_CURRENT 切换；终止灰度 ABORTED 旧版续用
 * - 批量口径校验（14.3.3）：PUBLISHED 模板 × 当前版现算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricDictServiceImpl implements MetricDictService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** spec 6.3.1 编码格式：M-<域>-<序号>（仅新 key 强制，存量不追溯） */
    private static final Pattern METRIC_CODE = Pattern.compile("^M-[A-Z]{2,8}-\\d{3}$");
    private static final List<String> FORMULA_TYPES = List.of("RATIO", "PRODUCT", "ACCUM");
    /** 灰度切换容差（C-4.10-08，TOLERANCE_DEFAULT 0.5%） */
    private static final java.math.BigDecimal TOLERANCE_PCT = new java.math.BigDecimal("0.5");

    private final BiMetricDictDao dictDao;
    private final ApprovalEngine approvalEngine;
    private final MetricLineageService lineageService;
    private final JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 登记（D3 十二要素）

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
        // 公式类型枚举校验（spec 6.3.1）
        String formulaType = str(p.get("formulaType"));
        if (formulaType == null) {
            formulaType = BiMetricDict.FT_RATIO;
        } else {
            formulaType = formulaType.toUpperCase();
            if (!FORMULA_TYPES.contains(formulaType)) {
                throw new ServiceException(422, "公式类型仅 RATIO 比率/PRODUCT 连乘/ACCUM 累加：" + formulaType);
            }
        }

        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null && !METRIC_CODE.matcher(metricKey).matches()) {
            // 编码格式仅新登记强制（存量不追溯，spec 场景）
            throw new ServiceException(422, "指标编码格式须为 M-<域>-<序号>（如 M-SD-001）：" + metricKey);
        }
        BiMetricDict row = new BiMetricDict();
        row.setMetricKey(metricKey);
        row.setName(name);
        row.setDefinition(definition);
        row.setFormula(formula);
        row.setDataPrecision(p.get("dataPrecision") == null ? 2 : Integer.parseInt(String.valueOf(p.get("dataPrecision"))));
        row.setTimeDim(timeDim);
        row.setFilterCond(str(p.get("filterCond")));
        row.setSrcMapping(srcMapping);
        row.setFormulaType(formulaType);
        row.setNumerator(str(p.get("numerator")));
        row.setDenominator(str(p.get("denominator")));
        row.setDimensions(str(p.get("dimensions")));
        row.setOwner(str(p.get("owner")));
        row.setStatus(BiMetricDict.ST_DRAFT);
        row.setRemark(str(p.get("remark")));

        if (curr == null) {
            row.setVersion(1);
            row.setIsCurrent(true);
            row.setUsedBy("[]");
            dictDao.insert(row);
        } else {
            // 更新当前版本内容（未发布）；已发布走变更申请入口
            if (BiMetricDict.ST_PUBLISHED.equals(curr.getStatus())) {
                throw new ServiceException(422,
                        "已发布指标请走口径变更入口（14.3.2 变更申请，生成新版本并经审批与灰度）");
            }
            if (BiMetricDict.ST_GRAYING.equals(curr.getStatus())
                    || BiMetricDict.ST_CHANGE_PENDING.equals(curr.getStatus())) {
                throw new ServiceException(422, "该指标存在进行中的变更（" + curr.getStatus() + "），不可重复登记");
            }
            curr.setName(name);
            curr.setDefinition(definition);
            curr.setFormula(formula);
            curr.setDataPrecision(row.getDataPrecision());
            curr.setTimeDim(timeDim);
            curr.setFilterCond(row.getFilterCond());
            curr.setSrcMapping(srcMapping);
            curr.setFormulaType(row.getFormulaType());
            curr.setNumerator(row.getNumerator());
            curr.setDenominator(row.getDenominator());
            curr.setDimensions(row.getDimensions());
            curr.setOwner(row.getOwner());
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
        // 十二要素：RATIO 型分子分母必填——仅新指标首次发布强制（存量不追溯，D3）
        if (BiMetricDict.FT_RATIO.equals(curr.getFormulaType())
                && !hasPublishedHistory(metricKey)) {
            List<String> missing = new ArrayList<>();
            if (!StringUtils.hasText(curr.getNumerator())) { missing.add("分子 numerator"); }
            if (!StringUtils.hasText(curr.getDenominator())) { missing.add("分母 denominator（比率型必填）"); }
            if (!missing.isEmpty()) {
                throw new ServiceException(422, "比率型指标要素缺失，拒绝发布：" + String.join("、", missing));
            }
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

    /** 批量口径校验（14.3.3）：PUBLISHED 模板 × 当前版，模板×指标×结论现算 */
    @Override
    public List<Map<String, Object>> checkAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> tpls = jdbc.queryForList(
                "SELECT ID, CODE, NAME, METRICS_JSON FROM erp_bi_report_template "
                        + "WHERE DEL_FLAG = '0' AND STATUS = 'PUBLISHED' ORDER BY CODE");
        for (Map<String, Object> t : tpls) {
            String json = t.get("METRICS_JSON") == null ? "" : String.valueOf(t.get("METRICS_JSON"));
            for (Map<String, Object> m : parseMetricsJson(json)) {
                String key = str(m.get("metricKey"));
                String f = str(m.get("formula"));
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("templateCode", String.valueOf(t.get("CODE")));
                row.put("templateName", String.valueOf(t.get("NAME")));
                row.put("metricKey", key);
                BiMetricDict dict = dictDao.selectCurrent(key);
                if (dict == null) {
                    row.put("registered", false);   // 未注册计为差异类问题（spec）
                    row.put("consistent", false);
                    row.put("report", "指标未注册口径");
                } else {
                    row.put("registered", true);
                    Map<String, Object> diff = doDiff(dict.getFormula(), f);
                    row.put("consistent", diff.get("consistent"));
                    row.put("report", diff.get("report"));
                }
                out.add(row);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 变更两段式治理（D2）

    @Override
    @Transactional
    public Map<String, Object> requestChange(String metricKey, Map<String, Object> p) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        if (!BiMetricDict.ST_PUBLISHED.equals(curr.getStatus())) {
            throw new ServiceException(422, "仅已发布指标可发起口径变更（当前：" + curr.getStatus() + "）");
        }
        // 同指标仅允许一个进行中变更
        Long inFlight = dictDao.selectCount(new LambdaQueryWrapper<BiMetricDict>()
                .eq(BiMetricDict::getMetricKey, metricKey)
                .in(BiMetricDict::getStatus,
                        BiMetricDict.ST_CHANGE_PENDING, BiMetricDict.ST_GRAYING));
        if (inFlight != null && inFlight > 0) {
            throw new ServiceException(422, "该指标已存在进行中的变更，请先处理（审批/切换/终止）");
        }
        String newFormula = str(p.get("formula"));
        if (!StringUtils.hasText(newFormula) || !isJson(newFormula)) {
            throw new ServiceException(422, "变更须提交合法 JSON 新公式");
        }
        int nextVer = (dictDao.selectMaxVersion(metricKey) == null ? 0
                : dictDao.selectMaxVersion(metricKey)) + 1;

        // 新版本行：要素继承 + payload 覆盖；IS_CURRENT=0（旧版生效），CHANGE_PENDING
        BiMetricDict fresh = new BiMetricDict();
        fresh.setMetricKey(metricKey);
        fresh.setName(str(p.get("name")) == null ? curr.getName() : str(p.get("name")));
        fresh.setDefinition(str(p.get("definition")) == null ? curr.getDefinition() : str(p.get("definition")));
        fresh.setFormula(newFormula);
        fresh.setDataPrecision(p.get("dataPrecision") == null ? curr.getDataPrecision()
                : Integer.parseInt(String.valueOf(p.get("dataPrecision"))));
        fresh.setTimeDim(str(p.get("timeDim")) == null ? curr.getTimeDim() : str(p.get("timeDim")));
        fresh.setFilterCond(str(p.get("filterCond")) == null ? curr.getFilterCond() : str(p.get("filterCond")));
        fresh.setSrcMapping(str(p.get("srcMapping")) == null ? curr.getSrcMapping() : str(p.get("srcMapping")));
        fresh.setFormulaType(str(p.get("formulaType")) == null ? curr.getFormulaType()
                : str(p.get("formulaType")).toUpperCase());
        if (!FORMULA_TYPES.contains(fresh.getFormulaType())) {
            throw new ServiceException(422, "公式类型仅 RATIO/PRODUCT/ACCUM");
        }
        fresh.setNumerator(str(p.get("numerator")) == null ? curr.getNumerator() : str(p.get("numerator")));
        fresh.setDenominator(str(p.get("denominator")) == null ? curr.getDenominator() : str(p.get("denominator")));
        fresh.setDimensions(str(p.get("dimensions")) == null ? curr.getDimensions() : str(p.get("dimensions")));
        fresh.setOwner(str(p.get("owner")) == null ? curr.getOwner() : str(p.get("owner")));
        fresh.setVersion(nextVer);
        fresh.setIsCurrent(false);
        fresh.setStatus(BiMetricDict.ST_CHANGE_PENDING);
        fresh.setUsedBy(curr.getUsedBy());
        fresh.setRemark("变更原因：" + (str(p.get("reason")) == null ? "-" : str(p.get("reason")))
                + "；影响范围：" + (str(p.get("scope")) == null ? "-" : str(p.get("scope"))));
        try {
            dictDao.insert(fresh);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(422, "版本冲突，请刷新后重试：" + nextVer);
        }
        // 血缘快照（新版本号，spec bi-metric-lineage）
        lineageService.collectAndSnapshot(metricKey, nextVer);

        // 治理单签（场景四：数据治理审核一致性）
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-口径一致性")));
        var inst = approvalEngine.submit("MetricDictChange", fresh.getId(),
                "口径变更审批：" + fresh.getName() + " v" + nextVer, "ROLE_DATA_GOV", chain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fresh.getId());
        out.put("metricKey", metricKey);
        out.put("version", nextVer);
        out.put("status", fresh.getStatus());
        out.put("approvalInstanceId", inst.getId());
        log.info("metric change requested {} v{} by {}", metricKey, nextVer, operator());
        return out;
    }

    @Override
    public List<Map<String, Object>> changes(String metricKey) {
        List<BiMetricDict> rows = dictDao.selectList(new LambdaQueryWrapper<BiMetricDict>()
                .eq(BiMetricDict::getMetricKey, metricKey)
                .in(BiMetricDict::getStatus,
                        BiMetricDict.ST_CHANGE_PENDING, BiMetricDict.ST_GRAYING,
                        BiMetricDict.ST_ABORTED)
                .orderByDesc(BiMetricDict::getVersion));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiMetricDict r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("metricKey", r.getMetricKey());
            m.put("name", r.getName());
            m.put("version", r.getVersion());
            m.put("status", r.getStatus());
            m.put("formula", r.getFormula());
            m.put("formulaType", r.getFormulaType());
            m.put("remark", r.getRemark());
            m.put("createBy", r.getCreateBy());
            m.put("createDate", r.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> switchConfirm(String rowId, String diffNote,
                                             String diffPct, String reviewNote) {
        BiMetricDict fresh = requireChangeRow(rowId);
        if (!BiMetricDict.ST_GRAYING.equals(fresh.getStatus())) {
            throw new ServiceException(422, "仅灰度中版本可发起切换确认（当前：" + fresh.getStatus() + "）");
        }
        if (!StringUtils.hasText(diffNote)) {
            throw new ServiceException(422, "切换确认须填写新旧口径对比说明");
        }
        // 容差外须治理复核备注（C-4.10-08 / 异常边界表）
        if (StringUtils.hasText(diffPct)) {
            try {
                java.math.BigDecimal pct = new java.math.BigDecimal(diffPct.trim());
                if (pct.abs().compareTo(TOLERANCE_PCT) > 0 && !StringUtils.hasText(reviewNote)) {
                    throw new ServiceException(422,
                            "新旧口径结果差异 " + pct + "% 超容差 0.5%，须附数据治理复核备注");
                }
            } catch (NumberFormatException e) {
                throw new ServiceException(422, "差异百分比格式非法：" + diffPct);
            }
        }
        fresh.setRemark(appendNote(fresh.getRemark(), "切换说明：" + diffNote
                + "；差异：" + (StringUtils.hasText(diffPct) ? diffPct + "%" : "未填")
                + (StringUtils.hasText(reviewNote) ? "；治理复核：" + reviewNote : "")));
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);

        // C-4.10-08 双审：数据治理 + 业务负责人
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-灰度复核")),
                List.of(ApprovalNodeSpec.sign("ROLE_BIZ_OWNER", "业务负责人-切换批准")));
        var inst = approvalEngine.submit("MetricDictSwitch", rowId,
                "口径切换确认：" + fresh.getName() + " v" + fresh.getVersion(),
                "ROLE_DATA_GOV", chain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rowId);
        out.put("status", fresh.getStatus());
        out.put("approvalInstanceId", inst.getId());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> abortChange(String rowId, String reason) {
        BiMetricDict fresh = requireChangeRow(rowId);
        if (!BiMetricDict.ST_GRAYING.equals(fresh.getStatus())) {
            throw new ServiceException(422, "仅灰度中变更可终止（审批中的请驳回）：" + fresh.getStatus());
        }
        fresh.setStatus(BiMetricDict.ST_ABORTED);
        fresh.setRemark(appendNote(fresh.getRemark(),
                "灰度终止：" + (StringUtils.hasText(reason) ? reason : "-")));
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);
        log.info("metric change aborted {} v{} by {}", fresh.getMetricKey(),
                fresh.getVersion(), operator());
        return Map.of("id", rowId, "status", fresh.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> confirmConsumer(String metricKey, String consumerId) {
        BiMetricDict curr = dictDao.selectCurrent(metricKey);
        if (curr == null) {
            throw new ServiceException(404, "指标不存在：" + metricKey);
        }
        List<Map<String, Object>> consumers = parseConsumers(curr.getUsedBy());
        boolean found = false;
        for (Map<String, Object> c : consumers) {
            if (consumerId.equals(c.get("id"))) {
                c.put("status", "ACTIVE");
                found = true;
            }
        }
        if (!found) {
            throw new ServiceException(404, "引用方未登记：" + consumerId);
        }
        curr.setUsedBy(toJson(consumers));
        curr.setUpdateBy(operator());
        dictDao.updateById(curr);
        return Map.of("metricKey", metricKey, "consumerId", consumerId, "status", "ACTIVE");
    }

    // ---------------------------------------------------------------- 回调入口

    /** 治理单签通过：CHANGE_PENDING → GRAYING + 反扫引用方 + 通知（旧版仍 IS_CURRENT） */
    @Override
    @Transactional
    public void onGovernanceApproved(String rowId) {
        BiMetricDict fresh = requireChangeRow(rowId);
        fresh.setStatus(BiMetricDict.ST_GRAYING);
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);
        // 反扫引用方标记存于当前生效行（consumerStatus 读取面），新版行同步一份
        BiMetricDict curr = dictDao.selectCurrent(fresh.getMetricKey());
        if (curr != null && !curr.getId().equals(fresh.getId())) {
            List<Map<String, Object>> consumers = parseConsumers(curr.getUsedBy());
            for (Map<String, Object> c : consumers) {
                c.put("status", "PENDING_UPDATE");
            }
            curr.setUsedBy(toJson(consumers));
            curr.setUpdateBy(operator());
            dictDao.updateById(curr);
            fresh.setUsedBy(curr.getUsedBy());
            dictDao.updateById(fresh);
        }
        notifyRole("ROLE_BI_DESIGNER", "MetricDictChange",
                "口径变更进入灰度：" + fresh.getName() + " v" + fresh.getVersion(),
                "旧口径继续生效；请按影响报告更新关联报表后发起切换确认（" + fresh.getRemark() + "）");
        log.info("metric change graying {} v{}", fresh.getMetricKey(), fresh.getVersion());
    }

    /** 治理驳回：→ DRAFT 留痕（可修改后重新提交） */
    @Override
    @Transactional
    public void onGovernanceRejected(String rowId) {
        BiMetricDict fresh = requireChangeRow(rowId);
        fresh.setStatus(BiMetricDict.ST_DRAFT);
        fresh.setRemark(appendNote(fresh.getRemark(), "治理审批驳回"));
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);
    }

    /** 切换双审通过：新版生效（IS_CURRENT 切换）+ 血缘刷新 + 通知 */
    @Override
    @Transactional
    public void applyApprovedChange(String rowId) {
        BiMetricDict fresh = requireChangeRow(rowId);
        if (!BiMetricDict.ST_GRAYING.equals(fresh.getStatus())) {
            throw new ServiceException(422, "非灰度中版本不可切换生效：" + fresh.getStatus());
        }
        BiMetricDict curr = dictDao.selectCurrent(fresh.getMetricKey());
        if (curr != null && !curr.getId().equals(fresh.getId())) {
            // 引用方清单以生效行为准（灰度期间可能新增绑定），随切换带到新版
            fresh.setUsedBy(curr.getUsedBy());
            curr.setIsCurrent(false);
            curr.setUpdateBy(operator());
            dictDao.updateById(curr);
        }
        fresh.setStatus(BiMetricDict.ST_PUBLISHED);
        fresh.setIsCurrent(true);
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);
        // 血缘按新版刷新快照（引用报表可能在灰度期增减）
        lineageService.collectAndSnapshot(fresh.getMetricKey(), fresh.getVersion());
        notifyRole("ROLE_BI_DESIGNER", "MetricDictSwitch",
                "口径切换生效：" + fresh.getName() + " v" + fresh.getVersion(),
                "新版口径已生效，引用方待更新标记随更新确认逐个清除");
        log.info("metric switch applied {} v{} isCurrent", fresh.getMetricKey(), fresh.getVersion());
    }

    /** 切换驳回：新版保持 GRAYING，可修正后重新发起切换 */
    @Override
    @Transactional
    public void onSwitchRejected(String rowId) {
        BiMetricDict fresh = requireChangeRow(rowId);
        fresh.setRemark(appendNote(fresh.getRemark(), "切换确认被驳回，保持灰度"));
        fresh.setUpdateBy(operator());
        dictDao.updateById(fresh);
    }

    // ---------------------------------------------------------------- 引用登记与查询

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

    @Override
    public Page<BiMetricDict> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<BiMetricDict> qw = new LambdaQueryWrapper<>();
        qw.eq(BiMetricDict::getIsCurrent, true);
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

    // ---------------------------------------------------------------- diff（C-4.10-01）

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
            JsonNode a = MAPPER.readTree(dictFormula == null ? "{}" : dictFormula);
            JsonNode b = MAPPER.readTree(submittedFormula);
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

    // ---------------------------------------------------------------- helpers

    private BiMetricDict requireChangeRow(String rowId) {
        BiMetricDict row = dictDao.selectById(rowId);
        if (row == null) {
            throw new ServiceException(404, "变更版本行不存在：" + rowId);
        }
        return row;
    }

    /** 是否已有 PUBLISHED 历史（新指标首次发布才强制十二要素新字段） */
    private boolean hasPublishedHistory(String metricKey) {
        Long n = dictDao.selectCount(new LambdaQueryWrapper<BiMetricDict>()
                .eq(BiMetricDict::getMetricKey, metricKey)
                .eq(BiMetricDict::getStatus, BiMetricDict.ST_PUBLISHED));
        return n != null && n > 0;
    }

    /** 角色广播告警（erp_sys_notice TARGET_ROLE，14.1/14.2 同列模式） */
    private void notifyRole(String role, String bizType, String title, String content) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, ?, ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    uuid(), role, bizType, uuid(), title,
                    content == null ? "" : content.substring(0, Math.min(900, content.length())));
        } catch (Exception e) {
            log.warn("metric dict notify failed: {}", e.getMessage());
        }
    }

    private static String appendNote(String base, String note) {
        if (base == null || base.trim().isEmpty()) {
            return note;
        }
        return base + "；" + note;
    }

    /** USED_BY 解析：[{id,status}] 对象数组；兼容旧字符串数组 */
    private List<Map<String, Object>> parseConsumers(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            List<Object> raw = MAPPER.readValue(json, new TypeReference<List<Object>>() { });
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

    /** METRICS_JSON 解析（checkAll 用） */
    private List<Map<String, Object>> parseMetricsJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isJson(String s) {
        try {
            MAPPER.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
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

package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.common.SqlGuard;
import com.erp.dao.bi.BiExtractBatchDao;
import com.erp.dao.bi.BiQualityResultDao;
import com.erp.dao.bi.BiQualityRuleDao;
import com.erp.entity.bi.BiExtractBatch;
import com.erp.entity.bi.BiQualityResult;
import com.erp.entity.bi.BiQualityRule;
import com.erp.service.bi.QualityCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 数据质量校验实现（add-data-extract-console，spec bi-quality-check，BR-4.10-14）。
 * 三类规则：NOT_NULL 非空率 / RECONCILE 勾稽+容差 / RANGE 越界计数。
 * 分级处置：SEVERE FAIL → 批次 QUALITY_BLOCKED；WARN → 告警放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckServiceImpl implements QualityCheckService {

    /** 表/列名白名单拼接（design D2 风险缓解：仅标识符 + information_schema 预校验） */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_]+$");

    private final BiQualityRuleDao ruleDao;
    private final BiQualityResultDao resultDao;
    private final BiExtractBatchDao batchDao;
    private final JdbcTemplate jdbc;

    // ---------- 规则 CRUD ----------

    @Override
    public List<Map<String, Object>> listRules(String status) {
        LambdaQueryWrapper<BiQualityRule> qw = new LambdaQueryWrapper<BiQualityRule>()
                .orderByAsc(BiQualityRule::getRuleType);
        if (StringUtils.hasText(status)) {
            qw.eq(BiQualityRule::getStatus, status);
        }
        List<BiQualityRule> list = ruleDao.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiQualityRule r : list) {
            out.add(ruleView(r));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createRule(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        String type = str(payload.get("ruleType"));
        if (!StringUtils.hasText(name) || !StringUtils.hasText(type)) {
            throw new ServiceException(422, "规则名称与类型必填");
        }
        BiQualityRule r = new BiQualityRule();
        r.setId(uuid());
        r.setName(name.trim());
        r.setRuleType(type);
        r.setDatasourceId(str(payload.get("datasourceId")));
        r.setTargetTable(str(payload.get("targetTable")));
        r.setTargetColumn(str(payload.get("targetColumn")));
        r.setLeftSql(str(payload.get("leftSql")));
        r.setRightSql(str(payload.get("rightSql")));
        r.setNotNullMin(dec(payload.get("notNullMin"), new BigDecimal("99.900")));
        r.setTolerance(dec(payload.get("tolerance"), BigDecimal.ZERO));
        r.setMinVal(dec(payload.get("minVal"), null));
        r.setMaxVal(dec(payload.get("maxVal"), null));
        r.setSevereLevel(StringUtils.hasText(str(payload.get("severeLevel")))
                ? str(payload.get("severeLevel")) : BiQualityRule.LVL_SEVERE);
        r.setStatus(BiQualityRule.ST_ENABLED);
        r.setRemark(str(payload.get("remark")));
        validateRule(r);
        ruleDao.insert(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> updateRule(String id, Map<String, Object> payload) {
        BiQualityRule r = requireRule(id);
        if (payload.get("name") != null) {
            r.setName(str(payload.get("name")));
        }
        if (payload.get("datasourceId") != null) {
            r.setDatasourceId(str(payload.get("datasourceId")));
        }
        if (payload.get("targetTable") != null) {
            r.setTargetTable(str(payload.get("targetTable")));
        }
        if (payload.get("targetColumn") != null) {
            r.setTargetColumn(str(payload.get("targetColumn")));
        }
        if (payload.get("leftSql") != null) {
            r.setLeftSql(str(payload.get("leftSql")));
        }
        if (payload.get("rightSql") != null) {
            r.setRightSql(str(payload.get("rightSql")));
        }
        if (payload.get("notNullMin") != null) {
            r.setNotNullMin(dec(payload.get("notNullMin"), r.getNotNullMin()));
        }
        if (payload.get("tolerance") != null) {
            r.setTolerance(dec(payload.get("tolerance"), r.getTolerance()));
        }
        if (payload.get("minVal") != null) {
            r.setMinVal(dec(payload.get("minVal"), null));
        }
        if (payload.get("maxVal") != null) {
            r.setMaxVal(dec(payload.get("maxVal"), null));
        }
        if (payload.get("severeLevel") != null) {
            String lv = str(payload.get("severeLevel"));
            if (!BiQualityRule.LVL_SEVERE.equals(lv) && !BiQualityRule.LVL_WARN.equals(lv)) {
                throw new ServiceException(422, "严重级别仅 SEVERE/WARN");
            }
            r.setSevereLevel(lv);
        }
        if (payload.get("status") != null) {
            String st = str(payload.get("status"));
            if (!BiQualityRule.ST_ENABLED.equals(st) && !BiQualityRule.ST_DISABLED.equals(st)) {
                throw new ServiceException(422, "状态仅 ENABLED/DISABLED");
            }
            r.setStatus(st);
        }
        if (payload.get("remark") != null) {
            r.setRemark(str(payload.get("remark")));
        }
        validateRule(r);
        r.setUpdateBy(operator());
        ruleDao.updateById(r);
        return Map.of("id", id);
    }

    @Override
    @Transactional
    public void deleteRule(String id) {
        BiQualityRule r = requireRule(id);
        Long used = resultDao.selectCount(new LambdaQueryWrapper<BiQualityResult>()
                .eq(BiQualityResult::getRuleId, id));
        if (used != null && used > 0) {
            throw new ServiceException(422, "规则存在 " + used + " 条校验报告，不可删除，请改用停用");
        }
        ruleDao.deleteById(id);
        log.info("质量规则删除 {} {} by {}", id, r.getName(), operator());
    }

    // ---------- 执行 ----------

    @Override
    @Transactional
    public Map<String, Object> runNow(String ruleId) {
        BiQualityRule r = requireRule(ruleId);
        BiQualityResult result = executeRule(r, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultId", result.getId());
        out.put("conclusion", result.getConclusion());
        out.put("expected", result.getExpected());
        out.put("actual", result.getActual());
        out.put("severeLevel", result.getSevereLevel());
        return out;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public int runForBatch(String batchNo, List<String> datasourceIds) {
        LambdaQueryWrapper<BiQualityRule> qw = new LambdaQueryWrapper<BiQualityRule>()
                .eq(BiQualityRule::getStatus, BiQualityRule.ST_ENABLED);
        List<BiQualityRule> rules = ruleDao.selectList(qw);
        int severeFail = 0;
        for (BiQualityRule r : rules) {
            if (datasourceIds != null && StringUtils.hasText(r.getDatasourceId())
                    && !datasourceIds.contains(r.getDatasourceId())) {
                continue; // 仅本批数据源范围
            }
            BiQualityResult res = executeRule(r, batchNo);
            if (BiQualityResult.CONC_FAIL.equals(res.getConclusion())
                    && BiQualityRule.LVL_SEVERE.equals(res.getSevereLevel())) {
                severeFail++;
            }
        }
        return severeFail;
    }

    @Override
    @Transactional
    public Map<String, Object> recheckBlockedBatch(String batchNo) {
        BiExtractBatch batch = batchByNo(batchNo);
        if (batch == null) {
            throw new ServiceException(404, "批次不存在：" + batchNo);
        }
        if (!BiExtractBatch.ST_QUALITY_BLOCKED.equals(batch.getStatus())) {
            throw new ServiceException(422, "批次非质量阻断状态：" + batch.getStatus());
        }
        List<String> dsIds = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DISTINCT c.DATASOURCE_ID AS dsId FROM erp_bi_extract_run r "
                        + "JOIN erp_bi_extract_config c ON c.ID = r.CONFIG_ID "
                        + "WHERE r.BATCH_NO = ?", batchNo);
        for (Map<String, Object> row : rows) {
            if (row.get("dsId") != null) {
                dsIds.add(String.valueOf(row.get("dsId")));
            }
        }
        int severeFail = runForBatch(batchNo, dsIds);
        if (severeFail == 0) {
            batch.setStatus(BiExtractBatch.ST_READY);
            batch.setReadyAt(LocalDateTime.now());
            batch.setUpdateBy(operator());
            batchDao.updateById(batch);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchNo", batchNo);
        out.put("status", batch.getStatus());
        out.put("severeFail", severeFail);
        return out;
    }

    @Override
    public List<Map<String, Object>> listResults(String batchNo, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, BATCH_NO AS batchNo, RULE_ID AS ruleId, RULE_NAME AS ruleName, "
                        + "RULE_TYPE AS ruleType, TARGET_DESC AS targetDesc, EXPECTED AS expected, "
                        + "ACTUAL AS actual, CONCLUSION AS conclusion, SEVERE_LEVEL AS severeLevel, "
                        + "EXEC_BY AS execBy, EXEC_AT AS execAt, ERROR_MSG AS errorMsg "
                        + "FROM erp_bi_quality_result WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(batchNo)) {
            sql.append("AND BATCH_NO = ? ");
            args.add(batchNo);
        }
        if (StringUtils.hasText(from)) {
            sql.append("AND EXEC_AT >= ? ");
            args.add(from + " 00:00:00");
        }
        if (StringUtils.hasText(to)) {
            sql.append("AND EXEC_AT <= ? ");
            args.add(to + " 23:59:59");
        }
        sql.append("ORDER BY EXEC_AT DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------- 内部执行 ----------

    /** 执行单规则并落报告；batchNo 空 = 手动校验 */
    private BiQualityResult executeRule(BiQualityRule r, String batchNo) {
        BiQualityResult res = new BiQualityResult();
        res.setId(uuid());
        res.setBatchNo(batchNo);
        res.setRuleId(r.getId());
        res.setRuleName(r.getName());
        res.setRuleType(r.getRuleType());
        res.setSevereLevel(r.getSevereLevel());
        res.setExecBy(operator());
        res.setExecAt(LocalDateTime.now());
        res.setCreateBy(operator());
        try {
            switch (r.getRuleType()) {
                case BiQualityRule.TYPE_NOT_NULL:
                    execNotNull(r, res);
                    break;
                case BiQualityRule.TYPE_RECONCILE:
                    execReconcile(r, res);
                    break;
                case BiQualityRule.TYPE_RANGE:
                    execRange(r, res);
                    break;
                default:
                    throw new ServiceException(422, "未知规则类型：" + r.getRuleType());
            }
        } catch (ServiceException e) {
            res.setConclusion(BiQualityResult.CONC_FAIL);
            res.setErrorMsg(e.getMessage().substring(0, Math.min(480, e.getMessage().length())));
        } catch (Exception e) {
            res.setConclusion(BiQualityResult.CONC_FAIL);
            res.setErrorMsg((e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .substring(0, Math.min(480,
                            (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).length())));
        }
        resultDao.insert(res);
        return res;
    }

    /** 完整性：非空率 ≥ 阈值（默认 99.9%，BR-4.10-14） */
    private void execNotNull(BiQualityRule r, BiQualityResult res) {
        String t = ident(r.getTargetTable(), "目标表");
        String c = ident(r.getTargetColumn(), "目标字段");
        requireColumnExists(t, c);
        res.setTargetDesc(t + "." + c);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class);
        Long notNull = jdbc.queryForObject(
                "SELECT COUNT(" + c + ") FROM " + t, Long.class);
        long tot = total == null ? 0L : total;
        long nn = notNull == null ? 0L : notNull;
        // 空表视为通过（无可校验数据）
        BigDecimal pct = tot == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(nn).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(tot), 3, RoundingMode.HALF_UP);
        res.setExpected("≥" + r.getNotNullMin() + "%");
        res.setActual(pct.toPlainString() + "%（" + nn + "/" + tot + "）");
        res.setConclusion(pct.compareTo(r.getNotNullMin()) >= 0
                ? BiQualityResult.CONC_PASS : BiQualityResult.CONC_FAIL);
    }

    /** 一致性：|左-右| ≤ 容差 */
    private void execReconcile(BiQualityRule r, BiQualityResult res) {
        SqlGuard.validate(r.getLeftSql());
        SqlGuard.validate(r.getRightSql());
        BigDecimal left = singleValue(r.getLeftSql());
        BigDecimal right = singleValue(r.getRightSql());
        BigDecimal diff = left.subtract(right).abs();
        res.setTargetDesc("L:" + abbrev(r.getLeftSql()) + " vs R:" + abbrev(r.getRightSql()));
        res.setExpected("|差值|≤" + r.getTolerance());
        res.setActual("左=" + left + " 右=" + right + " 差值=" + diff);
        res.setConclusion(diff.compareTo(r.getTolerance()) <= 0
                ? BiQualityResult.CONC_PASS : BiQualityResult.CONC_FAIL);
    }

    /** 准确性：越界行数须为 0（范围可空单边） */
    private void execRange(BiQualityRule r, BiQualityResult res) {
        String t = ident(r.getTargetTable(), "目标表");
        String c = ident(r.getTargetColumn(), "目标字段");
        requireColumnExists(t, c);
        if (r.getMinVal() == null && r.getMaxVal() == null) {
            throw new ServiceException(422, "RANGE 规则须至少登记上限或下限");
        }
        StringBuilder where = new StringBuilder(" WHERE ");
        List<String> conds = new ArrayList<>();
        if (r.getMinVal() != null) {
            conds.add(c + " < " + r.getMinVal().toPlainString());
        }
        if (r.getMaxVal() != null) {
            conds.add(c + " > " + r.getMaxVal().toPlainString());
        }
        where.append(String.join(" OR ", conds));
        Long bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + t + where, Long.class);
        long b = bad == null ? 0L : bad;
        res.setTargetDesc(t + "." + c);
        res.setExpected("越界行数=0（范围 " + (r.getMinVal() == null ? "-∞" : r.getMinVal())
                + "~" + (r.getMaxVal() == null ? "+∞" : r.getMaxVal()) + "）");
        res.setActual("越界 " + b + " 行");
        res.setConclusion(b == 0 ? BiQualityResult.CONC_PASS : BiQualityResult.CONC_FAIL);
    }

    private void validateRule(BiQualityRule r) {
        if (!BiQualityRule.LVL_SEVERE.equals(r.getSevereLevel())
                && !BiQualityRule.LVL_WARN.equals(r.getSevereLevel())) {
            throw new ServiceException(422, "严重级别仅 SEVERE/WARN");
        }
        switch (r.getRuleType()) {
            case BiQualityRule.TYPE_NOT_NULL: {
                String t = ident(r.getTargetTable(), "目标表");
                String c = ident(r.getTargetColumn(), "目标字段");
                requireColumnExists(t, c);
                if (r.getNotNullMin() == null || r.getNotNullMin().signum() <= 0
                        || r.getNotNullMin().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ServiceException(422, "非空率阈值须在 (0,100] 区间");
                }
                break;
            }
            case BiQualityRule.TYPE_RECONCILE: {
                SqlGuard.validate(r.getLeftSql());
                SqlGuard.validate(r.getRightSql());
                if (r.getTolerance() == null || r.getTolerance().signum() < 0) {
                    throw new ServiceException(422, "勾稽容差不得为负");
                }
                break;
            }
            case BiQualityRule.TYPE_RANGE: {
                String t = ident(r.getTargetTable(), "目标表");
                String c = ident(r.getTargetColumn(), "目标字段");
                requireColumnExists(t, c);
                if (r.getMinVal() == null && r.getMaxVal() == null) {
                    throw new ServiceException(422, "RANGE 规则须至少登记上限或下限");
                }
                if (r.getMinVal() != null && r.getMaxVal() != null
                        && r.getMinVal().compareTo(r.getMaxVal()) > 0) {
                    throw new ServiceException(422, "下限不得大于上限");
                }
                break;
            }
            default:
                throw new ServiceException(422, "规则类型仅 NOT_NULL/RECONCILE/RANGE");
        }
    }

    // ---------- helpers ----------

    private String ident(String v, String label) {
        if (!StringUtils.hasText(v) || !IDENT.matcher(v).matches()) {
            throw new ServiceException(422, label + "非法（仅字母数字下划线）：" + v);
        }
        return v;
    }

    private void requireColumnExists(String table, String column) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class, table, column);
        if (n == null || n == 0) {
            throw new ServiceException(422, "目标表/字段不存在：" + table + "." + column);
        }
    }

    /** 勾稽单值读取：取子查询首行首列（列名无关，兼容字面量 SELECT 1.0000） */
    private BigDecimal singleValue(String sql) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (" + sql + ") __q LIMIT 1");
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object v = rows.get(0).values().iterator().next();
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(v));
    }

    private String abbrev(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    private BiQualityRule requireRule(String id) {
        BiQualityRule r = ruleDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "质量规则不存在：" + id);
        }
        return r;
    }

    private BiExtractBatch batchByNo(String batchNo) {
        List<BiExtractBatch> list = batchDao.selectList(new LambdaQueryWrapper<BiExtractBatch>()
                .eq(BiExtractBatch::getBatchNo, batchNo).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> ruleView(BiQualityRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("ruleType", r.getRuleType());
        m.put("datasourceId", r.getDatasourceId());
        m.put("targetTable", r.getTargetTable());
        m.put("targetColumn", r.getTargetColumn());
        m.put("leftSql", r.getLeftSql());
        m.put("rightSql", r.getRightSql());
        m.put("notNullMin", r.getNotNullMin());
        m.put("tolerance", r.getTolerance());
        m.put("minVal", r.getMinVal());
        m.put("maxVal", r.getMaxVal());
        m.put("severeLevel", r.getSevereLevel());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        return m;
    }

    private BigDecimal dec(Object o, BigDecimal dflt) {
        if (o == null || !StringUtils.hasText(String.valueOf(o))) {
            return dflt;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
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

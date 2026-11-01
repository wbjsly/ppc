package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiAdhocTaskDao;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.dao.bi.BiReportTemplateDao;
import com.erp.dao.bi.BiSavedQueryDao;
import com.erp.entity.bi.AdhocTask;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiReportTemplate;
import com.erp.entity.bi.SavedQuery;
import com.erp.security.IntfGuard;
import com.erp.service.SysParamService;
import com.erp.service.bi.AdhocQueryBuilder;
import com.erp.service.bi.AdhocQueryService;
import com.erp.service.bi.ColumnSemantics;
import com.erp.service.bi.QueryGovernance;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 即席查询实现（add-adhoc-query-console，spec bi-adhoc-query）：
 * 数据集=PUBLISHED 模板；结构化构建（无自由 SQL 面）；预检硬阻断（BR-4.10-30）；
 * 成本预估转后台（BR-4.10-31）；治理执行（行级注入+掩码+超时 BR-4.10-32）；
 * 配额限流（BR-4.10-33）；REJECTED 逐次留痕（C-4.10-10）。
 */
@Slf4j
@Service
public class AdhocQueryServiceImpl implements AdhocQueryService {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 预估耗时启发：每万行 1 秒；超 30 秒转后台 */
    private static final long EST_MS_PER_10K = 1_000L;
    private static final long EST_MS_THRESHOLD = 30_000L;

    private final BiReportTemplateDao templateDao;
    private final BiAdhocTaskDao adhocTaskDao;
    private final BiSavedQueryDao savedQueryDao;
    private final BiMetricDictDao metricDictDao;
    private final QueryGovernance governance;
    private final SysParamService sysParam;
    private final JdbcTemplate jdbc;

    public AdhocQueryServiceImpl(BiReportTemplateDao templateDao,
                                 BiAdhocTaskDao adhocTaskDao,
                                 BiSavedQueryDao savedQueryDao,
                                 BiMetricDictDao metricDictDao,
                                 QueryGovernance governance,
                                 SysParamService sysParam,
                                 JdbcTemplate jdbc) {
        this.templateDao = templateDao;
        this.adhocTaskDao = adhocTaskDao;
        this.savedQueryDao = savedQueryDao;
        this.metricDictDao = metricDictDao;
        this.governance = governance;
        this.sysParam = sysParam;
        this.jdbc = jdbc;
    }

    // ---------- 数据集（spec：数据集选择与字段语义） ----------

    @Override
    public List<Map<String, Object>> datasets() {
        String user = IntfGuard.currentUser();
        List<String> allowed = governance.allowedEntities(user);
        List<Map<String, Object>> out = new ArrayList<>();
        if (allowed != null && allowed.isEmpty()) {
            return out; // 行级权限空集 → 无可见数据集（不可见即不可选）
        }
        List<BiReportTemplate> tpls = templateDao.selectList(
                new LambdaQueryWrapper<BiReportTemplate>()
                        .eq(BiReportTemplate::getStatus, BiReportTemplate.ST_PUBLISHED)
                        .orderByDesc(BiReportTemplate::getCreateDate));
        for (BiReportTemplate t : tpls) {
            List<ColumnSemantics.Col> cols = ColumnSemantics.parse(t.getColumnsJson());
            if (cols.isEmpty()) {
                continue;
            }
            markMetricRegistration(t, cols);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", t.getId());
            node.put("code", t.getCode());
            node.put("name", t.getName());
            node.put("category", t.getCategory());
            List<Map<String, Object>> dims = new ArrayList<>();
            List<Map<String, Object>> measures = new ArrayList<>();
            for (ColumnSemantics.Col c : cols) {
                Map<String, Object> tn = ColumnSemantics.toTreeNode(c);
                if (c.isMeasure()) {
                    measures.add(tn);
                } else {
                    dims.add(tn);
                }
            }
            node.put("dimensions", dims);
            node.put("measures", measures);
            out.add(node);
        }
        return out;
    }

    /** 指标注册标注（BR-4.10-01）：列语义含 metricKey 时查字典，未注册置 registered=false */
    private void markMetricRegistration(BiReportTemplate t, List<ColumnSemantics.Col> cols) {
        try {
            List<Map<String, Object>> metrics = parseList(t.getMetricsJson());
            for (Map<String, Object> m : metrics) {
                String key = str(m.get("metricKey"));
                if (key == null) {
                    continue;
                }
                BiMetricDict d = metricDictDao.selectCurrent(key);
                boolean registered = d != null;
                for (ColumnSemantics.Col c : cols) {
                    if (key.equalsIgnoreCase(c.field)) {
                        // 借 toTreeNode 输出前置于 agg 字段（前端置灰判定）
                        c.agg = registered ? c.agg : new ArrayList<>(List.of("UNREGISTERED"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("指标注册标注失败（不阻断数据集列表）: {}", e.getMessage());
        }
    }

    // ---------- 发起查询（spec：结构化构建与预检 + 成本预估转后台 + 配额） ----------

    // 注：此处刻意不加 @Transactional——REJECTED 留痕须在 422 抛出后仍持久
    // （C-4.10-10 逐次留痕），无事务时各 DAO 调用逐条自动提交。
    @Override
    public Map<String, Object> query(String templateId, String queryJson) {
        String user = IntfGuard.currentUser();
        BiReportTemplate t = requirePublished(templateId);
        List<ColumnSemantics.Col> cols = ColumnSemantics.parse(t.getColumnsJson());

        // 结构化构建（字段/聚合/op 白名单 + 值参数化；生成后过 SqlGuard）
        AdhocQueryBuilder.Built built = AdhocQueryBuilder.build(
                t.getSqlText(), cols, queryJson, rowLimit() + 1);

        // 预检 BR-4.10-30：显式时间/组织过滤任一为空 → REJECTED 留痕 + 422
        if (!built.hasTimeFilter || !built.hasOrgFilter) {
            String reason = !built.hasTimeFilter && !built.hasOrgFilter
                    ? "请补充时间或组织过滤条件" : (!built.hasTimeFilter
                    ? "请补充时间过滤条件" : "请补充组织过滤条件");
            record(user, t.getId(), queryJson, AdhocTask.ST_REJECTED, null, reason, null);
            throw new ServiceException(422, reason);
        }

        // 成本预估（EXPLAIN rows 求和；失败保守转后台）
        long[] est = estimate(built.sql, built.params);
        boolean forceQueued = est[0] < 0; // 预估失败 → 保守
        long estRows = Math.max(est[0], 0);
        long estMs = estRows / 10_000 * EST_MS_PER_10K;

        // 配额 BR-4.10-33：当日达限 → 强制入队（不阻断）
        long usedToday = adhocTaskDao.selectCount(new LambdaQueryWrapper<AdhocTask>()
                .eq(AdhocTask::getUserName, user)
                .ge(AdhocTask::getCreateDate, startOfDay()));
        long quota = sysParam.getInt("ADHOC_DAILY_QUOTA", 50);
        boolean quotaExhausted = usedToday >= quota;
        boolean quotaWarn = !quotaExhausted && usedToday >= quota * 4 / 5;

        // 转后台边界 BR-4.10-31：预估超限 或 超时阈值 或 预估失败 或 配额尽
        boolean toQueue = forceQueued || quotaExhausted
                || estRows > rowLimit() || estMs > EST_MS_THRESHOLD;
        if (toQueue) {
            AdhocTask task = enqueue(user, t.getId(), queryJson, estRows, estMs, quotaExhausted);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("mode", "queued");
            resp.put("taskId", task.getId());
            resp.put("taskNo", task.getTaskNo());
            resp.put("estRows", estRows);
            if (quotaExhausted) {
                resp.put("quotaMsg", "今日配额已用尽，后续查询将转后台执行");
            } else if (quotaWarn) {
                resp.put("quotaWarn", "今日配额已使用 80%");
            }
            return resp;
        }

        // 前台同步执行（超时/行级/掩码/截断；同步也落 DONE 行统一计数与留痕）
        Map<String, Object> data = executeGoverned(t, cols, queryJson, user);
        record(user, t.getId(), queryJson, AdhocTask.ST_DONE,
                ((Number) data.get("actualRows")).longValue(), null, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", "sync");
        resp.put("headers", data.get("headers"));
        resp.put("rows", data.get("rows"));
        resp.put("truncated", data.get("truncated"));
        if (quotaWarn) {
            resp.put("quotaWarn", "今日配额已使用 80%");
        }
        return resp;
    }

    // ---------- 任务查询 ----------

    @Override
    public List<Map<String, Object>> myTasks() {
        String user = IntfGuard.currentUser();
        List<AdhocTask> list = adhocTaskDao.selectList(new LambdaQueryWrapper<AdhocTask>()
                .eq(AdhocTask::getUserName, user)
                .orderByDesc(AdhocTask::getCreateDate)
                .last("LIMIT 100"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdhocTask a : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("taskNo", a.getTaskNo());
            m.put("templateId", a.getTemplateId());
            m.put("status", a.getStatus());
            m.put("estRows", a.getEstRows());
            m.put("actualRows", a.getActualRows());
            m.put("execMs", a.getExecMs());
            m.put("errorMsg", a.getErrorMsg());
            m.put("rejectReason", a.getRejectReason());
            m.put("createDate", a.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> taskDetail(String taskId, int page, int size) {
        AdhocTask a = requireTask(taskId);
        String user = IntfGuard.currentUser();
        boolean isAdmin = IntfGuard.isAdmin();
        if (!user.equals(a.getUserName()) && !isAdmin) {
            throw new ServiceException(404, "任务不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("taskNo", a.getTaskNo());
        out.put("status", a.getStatus());
        out.put("actualRows", a.getActualRows());
        out.put("execMs", a.getExecMs());
        out.put("errorMsg", a.getErrorMsg());
        // 结果集分页（RESULT_JSON 内存分页；7 天清理后为空）
        List<Map<String, Object>> all = parseList(a.getResultJson());
        int from = Math.max(0, (Math.max(1, page) - 1) * Math.max(1, size));
        int to = Math.min(all.size(), from + Math.max(1, size));
        out.put("total", all.size());
        out.put("page", Math.max(1, page));
        out.put("size", Math.max(1, size));
        out.put("rows", from < all.size() ? all.subList(from, to) : List.of());
        out.put("resultAvailable", a.getResultJson() != null);
        return out;
    }

    @Override
    public Map<String, Object> governance() {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = IntfGuard.currentUser();
        long quota = sysParam.getInt("ADHOC_DAILY_QUOTA", 50);
        long usedToday = adhocTaskDao.selectCount(new LambdaQueryWrapper<AdhocTask>()
                .eq(AdhocTask::getUserName, user)
                .ge(AdhocTask::getCreateDate, startOfDay()));
        out.put("quota", quota);
        out.put("usedToday", usedToday);
        out.put("remaining", Math.max(0, quota - usedToday));
        // 用量汇总（按用户：次数/扫描量/转后台次数）
        List<Map<String, Object>> usage = jdbc.queryForList(
                "SELECT USER_NAME AS userName, COUNT(*) AS total,"
                        + " SUM(CASE WHEN STATUS='REJECTED' THEN 1 ELSE 0 END) AS rejected,"
                        + " SUM(COALESCE(ACTUAL_ROWS,0)) AS scanned,"
                        + " SUM(CASE WHEN STATUS IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END) AS queued"
                        + " FROM erp_bi_adhoc_task WHERE DEL_FLAG='0'"
                        + " GROUP BY USER_NAME ORDER BY total DESC LIMIT 50");
        out.put("usage", usage);
        return out;
    }

    @Override
    public Map<String, Object> rerunFor(String templateId, String queryJson, String asUser,
                                        int page, int size) {
        BiReportTemplate t = requirePublished(templateId);
        List<ColumnSemantics.Col> cols = ColumnSemantics.parse(t.getColumnsJson());
        Map<String, Object> data = executeGoverned(t, cols, queryJson, asUser);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) data.get("rowsRaw");
        int pg = Math.max(1, page);
        int ps = Math.max(1, size);
        int from = (pg - 1) * ps;
        int to = Math.min(all.size(), from + ps);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", data.get("headers"));
        out.put("total", all.size());
        out.put("page", pg);
        out.put("size", ps);
        out.put("rows", from < all.size() ? all.subList(from, to) : List.of());
        out.put("truncated", data.get("truncated"));
        return out;
    }

    // ---------- 我的查询 ----------

    @Override
    public List<Map<String, Object>> savedQueries() {
        String user = IntfGuard.currentUser();
        List<SavedQuery> list = savedQueryDao.selectList(new LambdaQueryWrapper<SavedQuery>()
                .eq(SavedQuery::getUserName, user)
                .orderByDesc(SavedQuery::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SavedQuery s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("templateId", s.getTemplateId());
            m.put("queryJson", s.getQueryJson());
            m.put("createDate", s.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveQuery(Map<String, Object> payload) {
        String user = IntfGuard.currentUser();
        String name = str(payload.get("name"));
        String templateId = str(payload.get("templateId"));
        String queryJson = str(payload.get("queryJson"));
        if (name == null || name.isBlank()) {
            throw new ServiceException(422, "名称必填");
        }
        requirePublished(templateId); // 保存时校验数据集仍 PUBLISHED
        SavedQuery s = new SavedQuery();
        s.setId(uuid());
        s.setName(name);
        s.setUserName(user);
        s.setTemplateId(templateId);
        s.setQueryJson(queryJson);
        s.setCreateBy(user);
        savedQueryDao.insert(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSavedQuery(String id) {
        String user = IntfGuard.currentUser();
        SavedQuery s = savedQueryDao.selectById(id);
        if (s == null || !user.equals(s.getUserName())) {
            throw new ServiceException(404, "记录不存在");
        }
        savedQueryDao.deleteById(id);
    }

    // ---------- 入队与 worker 执行（供 AdhocScheduler 消费） ----------

    /** 入队（同步路径与 worker 均可调；worker 调 executeTaskById） */
    public AdhocTask enqueue(String user, String templateId, String queryJson,
                             long estRows, long estMs, boolean quotaExhausted) {
        AdhocTask task = new AdhocTask();
        task.setId(uuid());
        task.setTaskNo("ADH" + System.nanoTime());
        task.setUserName(user);
        task.setTemplateId(templateId);
        task.setQueryJson(queryJson);
        task.setStatus(AdhocTask.ST_QUEUED);
        task.setPriority(estRows > rowLimit() * 5 ? AdhocTask.PRI_LOW
                : estRows > rowLimit() ? AdhocTask.PRI_NORMAL : AdhocTask.PRI_SMALL);
        task.setEstRows(estRows);
        task.setEstMs(estMs);
        task.setTruncated(false);
        task.setNotifyFlag(false);
        task.setCreateBy(user);
        adhocTaskDao.insert(task);
        return task;
    }

    /** worker：执行单个后台任务（按任务属主身份治理执行） */
    @Transactional(rollbackFor = Exception.class)
    public void executeTaskById(String taskId) {
        AdhocTask a = adhocTaskDao.selectById(taskId);
        if (a == null || !AdhocTask.ST_QUEUED.equals(a.getStatus())) {
            return;
        }
        a.setStatus(AdhocTask.ST_RUNNING);
        adhocTaskDao.updateById(a);
        try {
            BiReportTemplate t = requirePublished(a.getTemplateId());
            List<ColumnSemantics.Col> cols = ColumnSemantics.parse(t.getColumnsJson());
            Map<String, Object> data = executeGoverned(t, cols, a.getQueryJson(),
                    a.getUserName());
            a.setStatus(AdhocTask.ST_DONE);
            a.setActualRows((Long) data.get("actualRows"));
            a.setExecMs((Long) data.get("execMs"));
            a.setTruncated((Boolean) data.get("truncated"));
            a.setResultJson(JSON.writeValueAsString(data.get("rowsRaw")));
            a.setNotifyFlag(false); // scheduler 发通知后置位
        } catch (ServiceException e) {
            a.setStatus(AdhocTask.ST_FAILED);
            a.setErrorMsg(e.getCode() + " " + e.getMessage());
        } catch (Exception e) {
            if (isTimeout(e)) {
                a.setStatus(AdhocTask.ST_TIMEOUT);
                a.setErrorMsg("查询超时（ADHOC_QUERY_TIMEOUT），请收窄范围或转后台执行");
            } else {
                log.warn("adhoc task {} failed: {}", taskId, e.getMessage());
                a.setStatus(AdhocTask.ST_FAILED);
                a.setErrorMsg(truncate(e.getMessage(), 900));
            }
        }
        adhocTaskDao.updateById(a);
    }

    /** 超时判定：SQLTimeoutException 或 DataAccessException cause 链上命中 */
    private boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLTimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Query execution was interrupted")
                    || msg.contains("timed out") || msg.contains("timeout"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    // ---------- 内部：治理执行（行级注入+掩码+超时+截断） ----------

    /**
     * 按访问者身份治理执行。
     * 行级：allowedEntities 空集 403；实体列存在且非全量时先注入内层模板 SQL
     * （聚合前过滤），再结构化构建（参数顺序天然正确）。
     * 超时：JDBC queryTimeout=ADHOC_QUERY_TIMEOUT（BR-4.10-32）。
     * 掩码：维度列逐格 maskIfHit（C-4.10-02）。
     */
    private Map<String, Object> executeGoverned(BiReportTemplate t,
                                                List<ColumnSemantics.Col> cols,
                                                String queryJson,
                                                String asUser) {
        List<String> allowed = governance.allowedEntities(asUser);
        if (allowed != null && allowed.isEmpty()) {
            throw new ServiceException(403, "行级权限不足：无有效授权，查询被拒绝");
        }
        // 行级实体注入：内层模板 SQL 先过滤（聚合前），再结构化构建
        String inner = t.getSqlText().replaceAll(";\\s*$", "").trim();
        String entityCol = entityCol(cols);
        AdhocQueryBuilder.Built exec;
        if (allowed != null && entityCol != null) {
            String filtered = "SELECT * FROM (" + inner + ") __r WHERE " + entityCol
                    + " IN (" + placeholders(allowed.size()) + ")";
            exec = AdhocQueryBuilder.build(filtered, cols, queryJson, rowLimit() + 1);
            // 实体内层占位在前、外层筛选占位在后
            List<Object> merged = new ArrayList<>(allowed);
            merged.addAll(exec.params);
            exec.params = merged;
        } else {
            exec = AdhocQueryBuilder.build(inner, cols, queryJson, rowLimit() + 1);
        }
        int timeout = sysParam.getInt("ADHOC_QUERY_TIMEOUT", 120);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = executeWithTimeout(exec.sql, exec.params, timeout);
        long execMs = System.currentTimeMillis() - start;
        int rowLimit = rowLimit();
        boolean truncated = rows.size() > rowLimit;
        if (truncated) {
            rows = rows.subList(0, rowLimit);
        }
        // 掩码（维度列名逐格；指标别名 mN 为聚合值不掩码）
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String k : row.keySet()) {
                if (!headers.contains(k)) {
                    headers.add(k);
                }
            }
        }
        List<List<Object>> aligned = new ArrayList<>();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> line = new ArrayList<>();
            Map<String, Object> rawRow = new LinkedHashMap<>();
            for (String h : headers) {
                Object v = governance.maskIfHit(h, row.get(h));
                line.add(v);
                rawRow.put(h, v);
            }
            aligned.add(line);
            raw.add(rawRow);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", headers);
        out.put("rows", aligned);
        out.put("rowsRaw", raw);
        out.put("truncated", truncated);
        out.put("actualRows", (long) aligned.size());
        out.put("execMs", execMs);
        return out;
    }

    /** 带超时的参数化执行（SQLTimeoutException 上抛 → TIMEOUT 语义） */
    private List<Map<String, Object>> executeWithTimeout(String sql, List<Object> params,
                                                         int timeoutSec) {
        return jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setQueryTimeout(timeoutSec);
                int i = 1;
                for (Object p : params) {
                    ps.setObject(i++, p);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int n = rs.getMetaData().getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= n; c++) {
                            row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    // ---------- 内部工具 ----------

    /** 成本预估：EXPLAIN rows 求和；失败返回 [-1, 0]（保守转后台） */
    private long[] estimate(String sql, List<Object> params) {
        try {
            List<Map<String, Object>> plan = jdbc.queryForList(
                    "EXPLAIN " + sql, params.toArray());
            long rows = 0;
            for (Map<String, Object> p : plan) {
                Object r = p.get("rows");
                if (r instanceof Number n) {
                    rows += n.longValue();
                }
            }
            return new long[] {rows, 0};
        } catch (Exception e) {
            log.warn("成本预估失败（保守转后台）: {}", e.getMessage());
            return new long[] {-1, 0};
        }
    }

    private void record(String user, String templateId, String queryJson, String status,
                        Long actualRows, String rejectReason, String errorMsg) {
        AdhocTask t = new AdhocTask();
        t.setId(uuid());
        t.setTaskNo("ADH" + System.nanoTime());
        t.setUserName(user);
        t.setTemplateId(templateId);
        t.setQueryJson(queryJson);
        t.setStatus(status);
        t.setPriority(AdhocTask.PRI_NORMAL);
        t.setActualRows(actualRows);
        t.setTruncated(false);
        t.setNotifyFlag(true); // 同步/阻断无需通知
        t.setRejectReason(rejectReason);
        t.setErrorMsg(errorMsg);
        t.setCreateBy(user);
        adhocTaskDao.insert(t);
    }

    private BiReportTemplate requirePublished(String templateId) {
        BiReportTemplate t = templateDao.selectById(templateId);
        if (t == null) {
            throw new ServiceException(404, "数据集不存在");
        }
        if (!BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅已发布模板可作为数据集");
        }
        return t;
    }

    private AdhocTask requireTask(String id) {
        AdhocTask a = adhocTaskDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "任务不存在");
        }
        return a;
    }

    private String entityCol(List<ColumnSemantics.Col> cols) {
        for (ColumnSemantics.Col c : cols) {
            if (c.entityCol) {
                return c.field;
            }
        }
        return null;
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private int rowLimit() {
        return sysParam.getInt("EXPORT_ROW_LIMIT", 10000);
    }

    private LocalDateTime startOfDay() {
        return LocalDate.now().atStartOfDay();
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                    List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}

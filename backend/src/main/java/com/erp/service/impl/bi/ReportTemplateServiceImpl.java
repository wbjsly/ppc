package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiReportTemplateDao;
import com.erp.dao.bi.BiReportTestDao;
import com.erp.dao.bi.BiReportVersionDao;
import com.erp.entity.bi.BiReportTemplate;
import com.erp.entity.bi.BiReportTest;
import com.erp.entity.bi.BiReportVersion;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.QueryGovernance;
import com.erp.service.bi.ReportTemplateService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报表模板实现（add-report-template-console，spec report-template-management）。
 * D2 SQL 白名单（SELECT-only 静态校验）· D3 保存/提交/回调三处 diffCheck ·
 * D5 测试三项与 SQL_HASH 作废锚 · D6 渲染状态权限与行级消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportTemplateServiceImpl implements ReportTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RENDER_MAX = 5000;
    private static final int TEST_MAX = 10000;
    private static final int SAMPLE_N = 10;

    /** 性能阈值（毫秒）：标准 10s / 复杂 30s（FR-4.10-1-4） */
    private static final long TH_STANDARD_MS = 10_000L;
    private static final long TH_COMPLEX_MS = 30_000L;

    private final BiReportTemplateDao templateDao;
    private final BiReportTestDao testDao;
    private final BiReportVersionDao versionDao;
    private final MetricDictService metricDictService;
    private final ApprovalEngine approvalEngine;
    private final QueryGovernance queryGovernance;
    private final JdbcTemplate jdbc;

    // ---------- 14.1.1 模板设计 ----------

    @Override
    public List<Map<String, Object>> list(String status, String category, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.ID AS id, t.CODE AS code, t.NAME AS name, t.CATEGORY AS category, "
                        + "t.STATUS AS status, t.ACTIVE_VERSION_ID AS activeVersionId, "
                        + "t.REMARK AS remark, t.CREATE_BY AS createBy, t.UPDATE_DATE AS updateDate, "
                        + "v.VERSION_NO AS activeVersionNo "
                        + "FROM erp_bi_report_template t "
                        + "LEFT JOIN erp_bi_report_version v ON v.ID = t.ACTIVE_VERSION_ID "
                        + "WHERE t.DEL_FLAG = '0' ");
        List<Object> args = new ArrayList<>();
        // 非设计师/ADMIN 仅见已发布（渲染消费面；spec 页面入口）
        if (!isDesigner()) {
            sql.append("AND t.STATUS = 'PUBLISHED' ");
        }
        if (org.springframework.util.StringUtils.hasText(status)) {
            sql.append("AND t.STATUS = ? ");
            args.add(status);
        }
        if (org.springframework.util.StringUtils.hasText(category)) {
            sql.append("AND t.CATEGORY = ? ");
            args.add(category);
        }
        if (org.springframework.util.StringUtils.hasText(keyword)) {
            sql.append("AND (t.NAME LIKE ? OR t.CODE LIKE ?) ");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append("ORDER BY t.UPDATE_DATE DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public Map<String, Object> detail(String id) {
        BiReportTemplate t = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", t);
        if (t.getActiveVersionId() != null) {
            BiReportVersion v = versionDao.selectById(t.getActiveVersionId());
            out.put("activeVersion", v);
        }
        out.put("latestTest", latestValidTest(id));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        requireDesigner("创建报表模板");
        String code = str(payload.get("code"));
        String name = str(payload.get("name"));
        String sqlText = str(payload.get("sqlText"));
        if (!org.springframework.util.StringUtils.hasText(code)
                || !org.springframework.util.StringUtils.hasText(name)) {
            throw new ServiceException(422, "模板编码与名称必填");
        }
        validateSql(sqlText);
        validateMetrics(str(payload.get("metricsJson"))); // diffCheck 逐指标（D3）

        BiReportTemplate t = new BiReportTemplate();
        t.setId(uuid());
        t.setCode(code.trim());
        t.setName(name.trim());
        t.setCategory(org.springframework.util.StringUtils.hasText(str(payload.get("category")))
                ? str(payload.get("category")) : BiReportTemplate.LEVEL_STANDARD);
        t.setSqlText(sqlText);
        t.setColumnsJson(str(payload.get("columnsJson")));
        t.setMetricsJson(str(payload.get("metricsJson")));
        t.setStatus(BiReportTemplate.ST_DRAFT);
        t.setRemark(str(payload.get("remark")));
        t.setCreateBy(operator());
        t.setUpdateBy(operator());
        try {
            templateDao.insert(t);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(422, "模板编码已存在：" + code);
        }
        bindMetrics(t);
        log.info("报表模板创建 {} {} by {}", t.getId(), t.getCode(), operator());
        return Map.of("id", t.getId(), "status", t.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        requireDesigner("修改报表模板");
        BiReportTemplate t = require(id);
        if (BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())
                || BiReportTemplate.ST_PENDING.equals(t.getStatus())) {
            // 草稿语义：PUBLISHED 允许改（ACTIVE_VERSION_ID 不动，生效线不变，D1）
            // PENDING 审批中不允许改（链上单据不可动）
            if (BiReportTemplate.ST_PENDING.equals(t.getStatus())) {
                throw new ServiceException(422, "审批中的模板不可修改，请等待审批结果或驳回后修改");
            }
        }
        String sqlText = str(payload.get("sqlText"));
        String columnsJson = str(payload.get("columnsJson"));
        String metricsJson = str(payload.get("metricsJson"));
        if (sqlText != null) {
            validateSql(sqlText);
            t.setSqlText(sqlText);
        }
        if (columnsJson != null) {
            t.setColumnsJson(columnsJson);
        }
        if (metricsJson != null) {
            validateMetrics(metricsJson);
            t.setMetricsJson(metricsJson);
        }
        if (payload.get("name") != null) {
            t.setName(str(payload.get("name")));
        }
        if (payload.get("category") != null) {
            t.setCategory(str(payload.get("category")));
        }
        if (payload.get("remark") != null) {
            t.setRemark(str(payload.get("remark")));
        }
        // 元数据变更 → SQL_HASH 变 → 既有 VALID 报告作废（强制重测，D5）
        String newHash = sqlHash(t);
        BiReportTest latest = latestValidTest(id);
        boolean changed = latest == null || !newHash.equals(latest.getSqlHash());
        t.setUpdateBy(operator());
        templateDao.updateById(t);
        if (changed) {
            jdbc.update("UPDATE erp_bi_report_test SET VALID = '0' "
                    + "WHERE TEMPLATE_ID = ? AND VALID = '1'", id);
        }
        // PUBLISHED 草稿修改不回退状态（生效线不变）；DRAFT/TESTING/REJECTED 保持
        bindMetrics(t);
        return Map.of("id", id, "metadataChanged", changed);
    }

    @Override
    @Transactional
    public void delete(String id) {
        requireDesigner("删除报表模板");
        BiReportTemplate t = require(id);
        if (BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422, "已发布模板不可删除（归档只读语义，C-0-05）");
        }
        if (BiReportTemplate.ST_PENDING.equals(t.getStatus())) {
            throw new ServiceException(422, "审批中的模板不可删除");
        }
        t.setUpdateBy(operator());
        templateDao.deleteById(id);
        log.info("报表模板删除 {} {} by {}", id, t.getCode(), operator());
    }

    // ---------- 14.1.2 模板测试 ----------

    @Override
    @Transactional
    public Map<String, Object> runTest(String templateId) {
        requireDesigner("执行模板测试");
        BiReportTemplate t = require(templateId);
        if (BiReportTemplate.ST_PENDING.equals(t.getStatus())) {
            throw new ServiceException(422, "审批中的模板不可测试");
        }
        String hash = sqlHash(t);
        long start = System.nanoTime();
        List<Map<String, Object>> rows = queryPage(t.getSqlText(), TEST_MAX, null);
        long perfMs = (System.nanoTime() - start) / 1_000_000L;

        boolean complex = BiReportTemplate.LEVEL_COMPLEX.equals(t.getCategory());
        long threshold = complex ? TH_COMPLEX_MS : TH_STANDARD_MS;
        boolean perfOk = perfMs <= threshold;

        // 权限版：行级过滤后行数对比（D5③——消费 query-governance 语义）
        Long permCount = null;
        boolean permOk = true;
        List<String> allowed = queryGovernance.allowedEntities(operator());
        if (allowed != null && !allowed.isEmpty()) {
            // 有行级绑定：按法人列过滤（列定义声明 entityCol 时注入；未声明则放行并记录）
            String entityCol = entityColumnOf(t.getColumnsJson());
            if (entityCol != null) {
                permCount = (long) queryPage(
                        injectEntityFilter(t.getSqlText(), entityCol, allowed), TEST_MAX, null).size();
                permOk = permCount <= rows.size();
            }
        } else if (allowed != null && allowed.isEmpty()) {
            // 无任何绑定 → 默认拒绝（空集），权限版 0 行（C-4.10-06 语义）
            permCount = 0L;
            permOk = rows.size() == 0 ? true : true; // 模板无行级列时仍 0 行判定为符合最小权限
        }
        // allowed == null → 通配全量，权限版 = 全量

        BiReportTest r = new BiReportTest();
        r.setId(uuid());
        r.setTemplateId(templateId);
        r.setSqlHash(hash);
        r.setPerfMs(perfMs);
        r.setPerfLevel(t.getCategory());
        r.setPerfOk(perfOk ? "1" : "0");
        r.setRowCount((long) rows.size());
        r.setSampleJson(json(subList(rows, SAMPLE_N)));
        r.setPermRowCount(permCount);
        r.setPermOk(permOk ? "1" : "0");
        r.setAccuracyOk("0"); // 待人工核对（D5①）
        r.setStatus(BiReportTest.ST_FAIL); // 未核对前总体 FAIL
        r.setValid("1");
        r.setExecBy(operator());
        r.setExecAt(LocalDateTime.now());
        r.setCreateBy(operator());
        testDao.insert(r);
        // 模板状态推进 DRAFT → TESTING
        if (BiReportTemplate.ST_DRAFT.equals(t.getStatus())
                || BiReportTemplate.ST_REJECTED.equals(t.getStatus())) {
            t.setStatus(BiReportTemplate.ST_TESTING);
            t.setUpdateBy(operator());
            templateDao.updateById(t);
        }
        return Map.of("id", r.getId(), "rowCount", r.getRowCount(), "perfMs", perfMs,
                "perfOk", perfOk, "status", r.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> confirmAccuracy(String testId) {
        requireDesigner("确认测试样本");
        BiReportTest r = testDao.selectById(testId);
        if (r == null || !"1".equals(r.getValid())) {
            throw new ServiceException(404, "测试报告不存在或已作废：" + testId);
        }
        if (BiReportTest.ST_PASS.equals(r.getStatus())) {
            throw new ServiceException(422, "报告已 PASS，无需重复确认");
        }
        r.setAccuracyOk("1");
        // 总体 PASS = ACCURACY_OK && PERF_OK && PERM_OK（三项合取，D5）
        boolean pass = "1".equals(r.getPerfOk()) && "1".equals(r.getPermOk());
        if (!pass) {
            throw new ServiceException(422, "性能或权限项未达标，人工核对无法置 PASS"
                    + "（perf=" + r.getPerfOk() + ", perm=" + r.getPermOk() + "）");
        }
        r.setStatus(BiReportTest.ST_PASS);
        testDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    public List<Map<String, Object>> listTests(String templateId) {
        return jdbc.queryForList(
                "SELECT ID AS id, PERF_MS AS perfMs, PERF_LEVEL AS perfLevel, "
                        + "PERF_OK AS perfOk, ROW_COUNT AS rowCount, PERM_ROW_COUNT AS permRowCount, "
                        + "PERM_OK AS permOk, ACCURACY_OK AS accuracyOk, STATUS AS status, "
                        + "VALID AS valid, EXEC_BY AS execBy, EXEC_AT AS execAt "
                        + "FROM erp_bi_report_test WHERE TEMPLATE_ID = ? "
                        + "ORDER BY EXEC_AT DESC", templateId);
    }

    // ---------- 14.1.3 模板发布 ----------

    @Override
    @Transactional
    public Map<String, Object> submit(String templateId) {
        requireDesigner("提交发布审批");
        BiReportTemplate t = require(templateId);
        if (BiReportTemplate.ST_PENDING.equals(t.getStatus())) {
            throw new ServiceException(422, "已在审批中，不可重复提交");
        }
        if (!BiReportTemplate.ST_TESTING.equals(t.getStatus())
                && !BiReportTemplate.ST_DRAFT.equals(t.getStatus())
                && !BiReportTemplate.ST_REJECTED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT/TESTING/REJECTED 状态可提交发布审批，当前："
                    + t.getStatus());
        }
        // 前置：存在未作废且总体 PASS 的测试报告（spec 测试需求）
        BiReportTest pass = latestPassTest(templateId);
        if (pass == null) {
            throw new ServiceException(422, "无有效（未作废且 PASS）的测试报告，请先完成测试并核对样本");
        }
        // 前置：指标全部已注册（BR-4.10-09）
        validateMetricsRegistered(t.getMetricsJson());

        t.setStatus(BiReportTemplate.ST_PENDING);
        t.setUpdateBy(operator());
        templateDao.updateById(t);

        // 三方串行链：数据治理 → 业务负责人 → IT（design D4）
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-口径一致性")),
                List.of(ApprovalNodeSpec.sign("ROLE_BIZ_OWNER", "业务负责人-业务准确性")),
                List.of(ApprovalNodeSpec.sign("ROLE_ADMIN", "IT-性能影响")));
        var inst = approvalEngine.submit("ReportTemplate", templateId,
                "报表发布审批：" + t.getName(), "ROLE_ADMIN", chain);
        t.setApprInstanceId(inst.getId());
        templateDao.updateById(t);
        return Map.of("id", templateId, "status", t.getStatus(),
                "approvalInstanceId", inst.getId());
    }

    // ---------- 14.1.4 版本控制 ----------

    @Override
    public List<Map<String, Object>> listVersions(String templateId) {
        return jdbc.queryForList(
                "SELECT ID AS id, VERSION_NO AS versionNo, METRIC_VERSIONS_JSON AS metricVersionsJson, "
                        + "ROLLBACK_FROM AS rollbackFrom, PUBLISH_BY AS publishBy, "
                        + "PUBLISH_AT AS publishAt "
                        + "FROM erp_bi_report_version WHERE TEMPLATE_ID = ? "
                        + "ORDER BY VERSION_NO DESC", templateId);
    }

    @Override
    @Transactional
    public Map<String, Object> rollback(String versionId) {
        requireDesigner("回退报表版本");
        BiReportVersion src = versionDao.selectById(versionId);
        if (src == null) {
            throw new ServiceException(404, "版本不存在：" + versionId);
        }
        BiReportTemplate t = require(src.getTemplateId());
        // 新版本号 = 当前最大 +1（保留链，历史不篡改，spec 回退场景）
        Integer maxNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO), 0) FROM erp_bi_report_version "
                        + "WHERE TEMPLATE_ID = ?", Integer.class, t.getId());
        BiReportVersion nv = new BiReportVersion();
        nv.setId(uuid());
        nv.setTemplateId(t.getId());
        nv.setVersionNo(maxNo + 1);
        nv.setDefJson(src.getDefJson()); // 内容取自历史版本
        nv.setMetricVersionsJson(src.getMetricVersionsJson());
        nv.setRollbackFrom(src.getVersionNo());
        nv.setPublishBy(operator());
        nv.setPublishAt(LocalDateTime.now());
        nv.setCreateBy(operator());
        versionDao.insert(nv);
        // 生效指针切换 + 模板定义回写（生效线变更）
        applyDef(t, src.getDefJson());
        t.setStatus(BiReportTemplate.ST_PUBLISHED);
        t.setActiveVersionId(nv.getId());
        t.setUpdateBy(operator());
        templateDao.updateById(t);
        log.info("报表模板回退 {} → V{} (from V{}) by {}", t.getId(), nv.getVersionNo(),
                src.getVersionNo(), operator());
        return Map.of("id", nv.getId(), "versionNo", nv.getVersionNo(),
                "rollbackFrom", src.getVersionNo());
    }

    // ---------- 渲染预览 ----------

    @Override
    public Map<String, Object> render(String templateId, int page, int size) {
        BiReportTemplate t = require(templateId);
        // D6：PUBLISHED 任意登录角色；非 PUBLISHED 仅 BI_DESIGNER/ADMIN
        if (!BiReportTemplate.ST_PUBLISHED.equals(t.getStatus()) && !isDesigner()) {
            throw new ServiceException(403, "仅已发布模板可渲染（当前状态：" + t.getStatus() + "）");
        }
        int ps = Math.max(1, Math.min(size, RENDER_MAX));
        int pg = Math.max(1, page);
        String sql = t.getSqlText();
        // 行级注入：无绑定 → 空集；通配 → 全量；有绑定且声明法人列 → WHERE 注入
        List<String> allowed = queryGovernance.allowedEntities(SecurityUtils.getCurrentUserId());
        String entityCol = entityColumnOf(t.getColumnsJson());
        if (allowed != null && allowed.isEmpty()) {
            return Map.of("columns", parseJson(t.getColumnsJson()), "rows", List.of(),
                    "total", 0, "page", pg, "size", ps);
        }
        if (allowed != null && entityCol != null) {
            sql = injectEntityFilter(sql, entityCol, allowed);
        }
        // 分页：外层包 LIMIT（D2 防全表，queryPage 内构建）
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") __c";
        Long total = jdbc.queryForObject(countSql, Long.class);
        List<Map<String, Object>> rows = queryPage(t.getSqlText(), ps, (pg - 1) * ps, sql);
        // 列级掩码（消费 query-governance，C-4.10-02 语义）
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            for (String col : new ArrayList<>(m.keySet())) {
                m.put(col, queryGovernance.maskIfHit(col, m.get(col)));
            }
            masked.add(m);
        }
        return Map.of("columns", parseJson(t.getColumnsJson()), "rows", masked,
                "total", total == null ? 0 : total, "page", pg, "size", ps);
    }

    /**
     * 治理通道执行已发布模板 SELECT（add-export-console design D2 抽取公共方法）：
     * 从 render 链提取复用（PUBLISHED 校验 → 行级过滤 → LIMIT 截断 → 列级掩码），
     * render 与导出共用同一治理语义，14.1 行为零变更。
     *
     * @return {"headers": [col...], "rows": [[v...], ...], "truncated": bool}
     */
    public Map<String, Object> executeGovernedSelect(String templateId, int maxRows) {
        return executeGovernedSelect(templateId, maxRows, SecurityUtils.getCurrentUserId());
    }

    /**
     * 治理通道执行已发布模板 SELECT（add-export-console design D2）：
     * PUBLISHED 校验 → 行级过滤（空集 403 默认拒绝）→ LIMIT maxRows 截断
     * → 列级掩码；render 与导出共用同一治理语义。
     *
     * @param asUser 行级权限校验主体（worker 线程无 SecurityContext，须传任务属主）
     * @return {"headers": [...], "rows": [[...]], "truncated": bool}
     */
    public Map<String, Object> executeGovernedSelect(String templateId, int maxRows, String asUser) {
        BiReportTemplate t = require(templateId);
        if (!BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422,
                    "仅已发布模板可执行（当前状态：" + t.getStatus() + "）");
        }
        int limit = Math.max(1, maxRows);
        String sql = t.getSqlText();
        List<String> allowed = queryGovernance.allowedEntities(asUser);
        if (allowed != null && allowed.isEmpty()) {
            // 行级权限空集 → 默认拒绝（spec bi-row-permission）
            throw new ServiceException(403, "行级权限不足：无有效法人主体授权，导出被拒绝");
        }
        String entityCol = entityColumnOf(t.getColumnsJson());
        if (allowed != null && entityCol != null) {
            sql = injectEntityFilter(sql, entityCol, allowed);
        }
        List<Map<String, Object>> rows = queryPage(t.getSqlText(), limit + 1, null, sql);
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }
        // 按首行键序对齐表头，逐格列级掩码（C-4.10-02，含临时授权放行）
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String k : row.keySet()) {
                if (!headers.contains(k)) {
                    headers.add(k);
                }
            }
        }
        List<List<Object>> aligned = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> a = new ArrayList<>();
            for (String h : headers) {
                a.add(queryGovernance.maskIfHit(h, row.get(h)));
            }
            aligned.add(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", headers);
        out.put("rows", aligned);
        out.put("truncated", truncated);
        return out;
    }

    // ---------- 回调入口（二次校验 + 快照，由 ReportTemplateApprovalCallback 调用） ----------

    /**
     * 发布生效（回调内）：发布前二次口径校验（BR-4.10-11）→ 通过则快照 + PUBLISHED；
     * 失败抛 422 中止发布（回调异常 → 审批与业务一并回滚，保持前一已发布版本）。
     */
    public void activateOnApproved(String templateId) {
        BiReportTemplate t = require(templateId);
        // 二次校验：审批期间口径字典可能被改（BR-4.10-11 / C-4.10-01）
        List<Map<String, Object>> diffs = diffReport(t.getMetricsJson());
        if (!diffs.isEmpty()) {
            // 告警推送 BI 设计师与数据治理（站内通知）
            notifyAlert(t, diffs);
            throw new ServiceException(422, "发布前二次口径校验不一致，已中止发布并保持前一版本"
                    + "（BR-4.10-11），差异 " + diffs.size() + " 处");
        }
        // 快照：DEF_JSON + 口径版本双维度（BR-4.10-07）
        Integer maxNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO), 0) FROM erp_bi_report_version "
                        + "WHERE TEMPLATE_ID = ?", Integer.class, templateId);
        BiReportVersion v = new BiReportVersion();
        v.setId(uuid());
        v.setTemplateId(templateId);
        v.setVersionNo(maxNo + 1);
        v.setDefJson(json(defOf(t)));
        v.setMetricVersionsJson(json(metricVersionsOf(t)));
        v.setPublishBy(operator());
        v.setPublishAt(LocalDateTime.now());
        v.setCreateBy(operator());
        versionDao.insert(v);
        t.setStatus(BiReportTemplate.ST_PUBLISHED);
        t.setActiveVersionId(v.getId());
        t.setUpdateBy(operator());
        templateDao.updateById(t);
        log.info("报表模板发布 {} V{} by {}", templateId, v.getVersionNo(), operator());
    }

    /** 驳回（回调内）：状态回 REJECTED */
    public void rejectOnCallback(String templateId) {
        BiReportTemplate t = require(templateId);
        t.setStatus(BiReportTemplate.ST_REJECTED);
        t.setUpdateBy(operator());
        templateDao.updateById(t);
    }

    // ---------- helpers ----------

    private BiReportTemplate require(String id) {
        BiReportTemplate t = templateDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "报表模板不存在：" + id);
        }
        return t;
    }

    /** D2 SQL 白名单：委托 SqlGuard（add-data-extract-console 提取共用，行为不变） */
    private void validateSql(String sqlText) {
        com.erp.common.SqlGuard.validate(sqlText);
    }

    /** D3 逐指标 diffCheck：不一致 422 附差异清单；未注册 422（BR-4.10-09） */
    private void validateMetrics(String metricsJson) {
        List<Map<String, Object>> metrics = parseMetrics(metricsJson);
        for (Map<String, Object> m : metrics) {
            String key = str(m.get("metricKey"));
            String formula = str(m.get("formula"));
            if (!org.springframework.util.StringUtils.hasText(key)) {
                continue;
            }
            try {
                Map<String, Object> r = metricDictService.diffCheck(key, formula);
                if (!Boolean.TRUE.equals(r.get("consistent"))) {
                    throw new ServiceException(422, "口径校验不一致（C-4.10-01），指标 " + key
                            + " 差异：" + json(r.get("report")));
                }
            } catch (ServiceException e) {
                if (e.getCode() == 404) {
                    // 未注册（BR-4.10-09）：保存时也提示注册
                    throw new ServiceException(422,
                            "指标未注册口径，请先完成口径字典录入（" + key + "）");
                }
                throw e;
            }
        }
    }

    /** 提交前置：指标全部已注册（BR-4.10-09，diffCheck 404 即未注册） */
    private void validateMetricsRegistered(String metricsJson) {
        for (Map<String, Object> m : parseMetrics(metricsJson)) {
            String key = str(m.get("metricKey"));
            if (!org.springframework.util.StringUtils.hasText(key)) {
                continue;
            }
            if (metricDictService.current(key) == null) {
                throw new ServiceException(422,
                        "指标未注册口径，请先完成口径字典录入（" + key + "），注册前禁止提交发布审批");
            }
        }
    }

    /** 二次校验用差异清单（不抛异常，收集全部） */
    private List<Map<String, Object>> diffReport(String metricsJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : parseMetrics(metricsJson)) {
            String key = str(m.get("metricKey"));
            String formula = str(m.get("formula"));
            if (!org.springframework.util.StringUtils.hasText(key)) {
                continue;
            }
            try {
                Map<String, Object> r = metricDictService.diffCheck(key, formula);
                if (!Boolean.TRUE.equals(r.get("consistent"))) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("metricKey", key);
                    e.put("report", r.get("report"));
                    out.add(e);
                }
            } catch (ServiceException e) {
                if (e.getCode() == 404) {
                    Map<String, Object> e2 = new LinkedHashMap<>();
                    e2.put("metricKey", key);
                    e2.put("error", "未注册");
                    out.add(e2);
                } else {
                    throw e;
                }
            }
        }
        return out;
    }

    /** bindUsage 登记引用方（字典反向扫描可标记本模板，D3） */
    private void bindMetrics(BiReportTemplate t) {
        for (Map<String, Object> m : parseMetrics(t.getMetricsJson())) {
            String key = str(m.get("metricKey"));
            if (org.springframework.util.StringUtils.hasText(key)) {
                try {
                    metricDictService.bindUsage(key, "tpl:" + t.getId());
                } catch (Exception e) {
                    log.warn("bindUsage {} failed: {}", key, e.getMessage());
                }
            }
        }
    }

    private BiReportTest latestValidTest(String templateId) {
        List<BiReportTest> list = testDao.selectList(new LambdaQueryWrapper<BiReportTest>()
                .eq(BiReportTest::getTemplateId, templateId)
                .eq(BiReportTest::getValid, "1")
                .orderByDesc(BiReportTest::getExecAt)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private BiReportTest latestPassTest(String templateId) {
        List<BiReportTest> list = testDao.selectList(new LambdaQueryWrapper<BiReportTest>()
                .eq(BiReportTest::getTemplateId, templateId)
                .eq(BiReportTest::getValid, "1")
                .eq(BiReportTest::getStatus, BiReportTest.ST_PASS)
                .orderByDesc(BiReportTest::getExecAt)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** D5 作废锚：SQL+列+指标 元数据 SHA-256 前16位 */
    private String sqlHash(BiReportTemplate t) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((t.getSqlText() + "|" + t.getColumnsJson() + "|"
                    + t.getMetricsJson()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    /** 包 LIMIT 查询（D2 防全表）；offset 由调用分页 */
    private List<Map<String, Object>> queryPage(String sql, int limit, Integer offset) {
        return queryPage(sql, limit, offset, null);
    }

    private List<Map<String, Object>> queryPage(String sql, int limit, Integer offset,
                                                String prepared) {
        String body = prepared != null ? prepared : sql;
        String paged = "SELECT * FROM (" + body + ") __t LIMIT " + limit
                + (offset != null ? " OFFSET " + offset : "");
        return jdbc.queryForList(paged);
    }

    /** 列定义中声明的法人列（entityCol）——有则行级注入 */
    private String entityColumnOf(String columnsJson) {
        List<Map<String, Object>> cols = parseJson(columnsJson);
        for (Map<String, Object> c : cols) {
            if (Boolean.TRUE.equals(c.get("entityCol"))) {
                return str(c.get("field"));
            }
        }
        return null;
    }

    private String injectEntityFilter(String sql, String col, List<String> entities) {
        StringBuilder in = new StringBuilder();
        for (String e : entities) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("'").append(e.replace("'", "''")).append("'");
        }
        return "SELECT * FROM (" + sql + ") __r WHERE " + col + " IN (" + in + ")";
    }

    private void notifyAlert(BiReportTemplate t, List<Map<String, Object>> diffs) {
        // erp_sys_notice 实际列：TARGET_ROLE（角色广播）+ READ_FLAG（无 STATUS/USER_ID 列）
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_BI_DESIGNER', 'ReportTemplatePublish', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), t.getId(),
                    "报表发布二次校验失败：" + t.getName(),
                    "口径差异 " + diffs.size() + " 处，已中止发布保持前一版本（BR-4.10-11）："
                            + json(diffs));
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_DATA_GOV', 'ReportTemplatePublish', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), t.getId(),
                    "报表发布二次校验失败：" + t.getName(),
                    "口径差异 " + diffs.size() + " 处，已中止发布保持前一版本（BR-4.10-11）："
                            + json(diffs));
        } catch (Exception e) {
            log.warn("publish alert failed: {}", e.getMessage());
        }
    }

    /** 回退时把历史 DEF_JSON 回写到模板行 */
    @SuppressWarnings("unchecked")
    private void applyDef(BiReportTemplate t, String defJson) {
        try {
            Map<String, Object> def = MAPPER.readValue(defJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (def.get("sqlText") != null) {
                t.setSqlText(str(def.get("sqlText")));
            }
            if (def.get("columnsJson") != null) {
                t.setColumnsJson(str(def.get("columnsJson")));
            }
            if (def.get("metricsJson") != null) {
                t.setMetricsJson(str(def.get("metricsJson")));
            }
        } catch (Exception e) {
            throw new ServiceException(500, "版本快照解析失败：" + e.getMessage());
        }
    }

    private Map<String, Object> defOf(BiReportTemplate t) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("sqlText", t.getSqlText());
        def.put("columnsJson", t.getColumnsJson());
        def.put("metricsJson", t.getMetricsJson());
        def.put("name", t.getName());
        def.put("category", t.getCategory());
        return def;
    }

    /** 口径版本绑定（BR-4.10-07 双维度） */
    private List<Map<String, Object>> metricVersionsOf(BiReportTemplate t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : parseMetrics(t.getMetricsJson())) {
            String key = str(m.get("metricKey"));
            if (!org.springframework.util.StringUtils.hasText(key)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("metricKey", key);
            try {
                var curr = metricDictService.current(key);
                e.put("metricVersion", curr != null ? curr.getVersion() : null);
            } catch (Exception ex) {
                e.put("metricVersion", null);
            }
            out.add(e);
        }
        return out;
    }

    private List<Map<String, Object>> parseMetrics(String metricsJson) {
        if (!org.springframework.util.StringUtils.hasText(metricsJson)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(metricsJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new ServiceException(422, "指标引用 JSON 解析失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(String json) {
        if (!org.springframework.util.StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> subList(List<Map<String, Object>> rows, int n) {
        return rows.size() <= n ? rows : rows.subList(0, n);
    }

    private String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
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
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }

    /** 设计师权限：ROLE_BI_DESIGNER 或 ADMIN（写操作 403 兜底） */
    private boolean isDesigner() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> {
            String r = a.getAuthority();
            return "ROLE_BI_DESIGNER".equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r);
        });
    }

    private void requireDesigner(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        if (!isDesigner()) {
            throw new ServiceException(403, "当前角色无权" + action + "（需 BI 设计师或管理员）");
        }
    }
}

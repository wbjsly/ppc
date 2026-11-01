package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.AesUtil;
import com.erp.common.ServiceException;
import com.erp.common.SqlGuard;
import com.erp.dao.bi.BiExtractBatchDao;
import com.erp.dao.bi.BiExtractConfigDao;
import com.erp.dao.bi.BiExtractRunDao;
import com.erp.dao.bi.BiDatasourceDao;
import com.erp.dao.bi.BiReportTemplateDao;
import com.erp.entity.bi.BiExtractBatch;
import com.erp.entity.bi.BiExtractConfig;
import com.erp.entity.bi.BiExtractRun;
import com.erp.entity.bi.BiDatasource;
import com.erp.entity.bi.BiReportTemplate;
import com.erp.service.bi.DataExtractService;
import com.erp.service.bi.QualityCheckService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 报表数据抽取实现（add-data-extract-console，spec bi-extract-console）。
 * D2 通用执行器：借鉴 051 采购管线机制语义（DAG 挂起 / 波动暂停 BR-4.10-12 /
 * 断点重试 BR-4.10-13 / 人工确认 / 就绪标志），独立实现不动老管线。
 * 抽取动作 = 治理 SQL 执行记行数（无中间数据落地，偏差表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExtractServiceImpl implements DataExtractService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final LocalTime WIN_FROM = LocalTime.of(1, 0);
    private static final LocalTime WIN_TO = LocalTime.of(5, 0);

    private final BiDatasourceDao datasourceDao;
    private final BiExtractConfigDao configDao;
    private final BiExtractBatchDao batchDao;
    private final BiExtractRunDao runDao;
    private final BiReportTemplateDao templateDao;
    private final QualityCheckService qualityCheckService;
    private final JdbcTemplate jdbc;

    @Value("${app.bi.extract-fluctuation-x:10}")
    private int fluctuationX;
    @Value("${app.bi.extract-retry-max:3}")
    private int retryMax;
    @Value("${app.bi.conn-secret:erp-bi-conn-default-secret}")
    private String connSecret;

    // ==================== 数据源（FR-4.10-2-1 / BR-4.10-15） ====================

    @Override
    public List<Map<String, Object>> listDatasources() {
        List<BiDatasource> list = datasourceDao.selectList(
                new LambdaQueryWrapper<BiDatasource>().orderByAsc(BiDatasource::getName));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiDatasource d : list) {
            out.add(dsView(d));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createDatasource(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        if (!StringUtils.hasText(name)) {
            throw new ServiceException(422, "数据源名称必填");
        }
        String type = StringUtils.hasText(str(payload.get("type")))
                ? str(payload.get("type")) : BiDatasource.TYPE_DB;
        BiDatasource d = new BiDatasource();
        d.setId(uuid());
        d.setName(name.trim());
        d.setType(type);
        d.setDbName(str(payload.get("dbName")));
        d.setRemark(str(payload.get("remark")));
        if (StringUtils.hasText(str(payload.get("conn")))) {
            // 连接信息加密存储（FR-4.10-2-1），明文不出接口
            d.setConnEnc(AesUtil.encrypt(str(payload.get("conn")), connSecret));
        }
        d.setStatus(BiDatasource.ST_ACTIVE);
        if (BiDatasource.TYPE_DB.equals(type)) {
            captureSnapshot(d); // 首登即采快照（变更检测基准）
        }
        try {
            datasourceDao.insert(d);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(422, "数据源名称已存在：" + name);
        }
        log.info("数据源创建 {} {} by {}", d.getId(), d.getName(), operator());
        return Map.of("id", d.getId(), "status", d.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> updateDatasource(String id, Map<String, Object> payload) {
        BiDatasource d = requireDs(id);
        if (payload.get("remark") != null) {
            d.setRemark(str(payload.get("remark")));
        }
        if (payload.get("dbName") != null) {
            d.setDbName(str(payload.get("dbName")));
        }
        if (StringUtils.hasText(str(payload.get("conn")))) {
            d.setConnEnc(AesUtil.encrypt(str(payload.get("conn")), connSecret));
        }
        if (payload.get("type") != null) {
            d.setType(str(payload.get("type")));
        }
        d.setUpdateBy(operator());
        datasourceDao.updateById(d);
        return Map.of("id", id);
    }

    @Override
    @Transactional
    public void deleteDatasource(String id) {
        BiDatasource d = requireDs(id);
        Long used = configDao.selectCount(new LambdaQueryWrapper<BiExtractConfig>()
                .eq(BiExtractConfig::getDatasourceId, id));
        if (used != null && used > 0) {
            throw new ServiceException(422, "数据源下存在 " + used + " 条抽取配置，不可删除");
        }
        datasourceDao.deleteById(id);
        log.info("数据源删除 {} {} by {}", id, d.getName(), operator());
    }

    @Override
    public Map<String, Object> testConnection(String id) {
        BiDatasource d = requireDs(id);
        if (BiDatasource.TYPE_DB.equals(d.getType())) {
            // DB：本库可达性 + 登记名核对（单库现实，逻辑数据源）
            String actual;
            try {
                actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
            } catch (Exception e) {
                return Map.of("ok", false, "msg", "数据库不可达：" + e.getMessage());
            }
            boolean nameOk = !StringUtils.hasText(d.getDbName()) || d.getDbName().equalsIgnoreCase(actual);
            return Map.of("ok", nameOk, "msg", nameOk
                    ? "本库可达，登记库名核对通过（" + actual + "）"
                    : "本库可达，但登记库名不一致：登记 " + d.getDbName() + " / 实际 " + actual);
        }
        return Map.of("ok", true, "msg", d.getType() + " 类型仅登记形态，不做真实外连（偏差表）");
    }

    @Override
    @Transactional
    public Map<String, Object> checkSchema(String id) {
        BiDatasource d = requireDs(id);
        if (!BiDatasource.TYPE_DB.equals(d.getType())) {
            return Map.of("changed", false, "msg", "非 DB 类型无 schema 快照");
        }
        Map<String, Object> current = currentSnapshot();
        Map<String, Object> stored = parseJsonMap(d.getSchemaSnapshot());
        List<String> diff = diffSnapshot(stored, current);
        if (diff.isEmpty()) {
            return Map.of("changed", false, "msg", "结构一致", "diff", List.of());
        }
        // BR-4.10-15：检测到变更 → 通知 BI 设计师 + 暂停其下配置自动执行
        d.setStatus(BiDatasource.ST_SCHEMA_CHANGED);
        d.setUpdateBy(operator());
        datasourceDao.updateById(d);
        notifyRole("ROLE_BI_DESIGNER", "DataExtractSchemaChange",
                "数据源结构变更：" + d.getName(),
                "schema 快照比对发现 " + diff.size() + " 处差异，其下抽取配置已暂停自动执行，"
                        + "请更新字段映射后刷新快照恢复（BR-4.10-15）：" + diff);
        log.warn("datasource schema changed {} diff={} by {}", id, diff.size(), operator());
        return Map.of("changed", true, "status", d.getStatus(), "diff", diff);
    }

    @Override
    @Transactional
    public Map<String, Object> refreshSnapshot(String id) {
        BiDatasource d = requireDs(id);
        if (!BiDatasource.TYPE_DB.equals(d.getType())) {
            throw new ServiceException(422, "非 DB 类型无 schema 快照");
        }
        captureSnapshot(d);
        d.setStatus(BiDatasource.ST_ACTIVE);
        d.setUpdateBy(operator());
        datasourceDao.updateById(d);
        return Map.of("id", id, "status", d.getStatus());
    }

    // ==================== 抽取配置 ====================

    @Override
    public List<Map<String, Object>> listConfigs(String datasourceId) {
        LambdaQueryWrapper<BiExtractConfig> qw = new LambdaQueryWrapper<BiExtractConfig>()
                .orderByAsc(BiExtractConfig::getSeq);
        if (StringUtils.hasText(datasourceId)) {
            qw.eq(BiExtractConfig::getDatasourceId, datasourceId);
        }
        List<BiExtractConfig> list = configDao.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiExtractConfig c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("datasourceId", c.getDatasourceId());
            m.put("templateId", c.getTemplateId());
            m.put("mode", c.getMode());
            m.put("watermarkField", c.getWatermarkField());
            m.put("lastWatermark", c.getLastWatermark());
            m.put("seq", c.getSeq());
            m.put("status", c.getStatus());
            m.put("remark", c.getRemark());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createConfig(Map<String, Object> payload) {
        String code = str(payload.get("code"));
        String name = str(payload.get("name"));
        String dsId = str(payload.get("datasourceId"));
        String tplId = str(payload.get("templateId"));
        if (!StringUtils.hasText(code) || !StringUtils.hasText(name)
                || !StringUtils.hasText(dsId) || !StringUtils.hasText(tplId)) {
            throw new ServiceException(422, "配置编码/名称/数据源/模板必填");
        }
        requireDs(dsId);
        BiReportTemplate tpl = requirePublishedTemplate(tplId);
        String mode = StringUtils.hasText(str(payload.get("mode")))
                ? str(payload.get("mode")) : BiExtractConfig.MODE_FULL;
        String wmField = str(payload.get("watermarkField"));
        validateWatermark(tpl, mode, wmField);

        BiExtractConfig c = new BiExtractConfig();
        c.setId(uuid());
        c.setCode(code.trim());
        c.setName(name.trim());
        c.setDatasourceId(dsId);
        c.setTemplateId(tplId);
        c.setMode(mode);
        c.setWatermarkField(wmField);
        c.setSeq(payload.get("seq") == null ? 10 : Integer.parseInt(str(payload.get("seq"))));
        c.setStatus(BiExtractConfig.ST_ENABLED);
        c.setRemark(str(payload.get("remark")));
        try {
            configDao.insert(c);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(422, "同数据源下配置编码已存在：" + code);
        }
        return Map.of("id", c.getId(), "status", c.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> updateConfig(String id, Map<String, Object> payload) {
        BiExtractConfig c = requireConfig(id);
        BiReportTemplate tpl = requirePublishedTemplate(c.getTemplateId());
        if (payload.get("name") != null) {
            c.setName(str(payload.get("name")));
        }
        if (payload.get("seq") != null) {
            c.setSeq(Integer.parseInt(str(payload.get("seq"))));
        }
        if (payload.get("status") != null) {
            String st = str(payload.get("status"));
            if (!BiExtractConfig.ST_ENABLED.equals(st) && !BiExtractConfig.ST_DISABLED.equals(st)) {
                throw new ServiceException(422, "状态仅 ENABLED/DISABLED");
            }
            c.setStatus(st);
        }
        if (payload.get("mode") != null || payload.get("watermarkField") != null) {
            String mode = payload.get("mode") != null ? str(payload.get("mode")) : c.getMode();
            String wm = payload.get("watermarkField") != null
                    ? str(payload.get("watermarkField")) : c.getWatermarkField();
            validateWatermark(tpl, mode, wm);
            c.setMode(mode);
            c.setWatermarkField(wm);
        }
        if (payload.get("remark") != null) {
            c.setRemark(str(payload.get("remark")));
        }
        c.setUpdateBy(operator());
        configDao.updateById(c);
        return Map.of("id", id);
    }

    @Override
    @Transactional
    public void deleteConfig(String id) {
        BiExtractConfig c = requireConfig(id);
        Long runs = runDao.selectCount(new LambdaQueryWrapper<BiExtractRun>()
                .eq(BiExtractRun::getConfigId, id));
        if (runs != null && runs > 0) {
            // 有执行历史：仅允许停用，不物理删（留痕语义）
            throw new ServiceException(422, "配置存在 " + runs + " 条执行记录，不可删除，请改用停用");
        }
        configDao.deleteById(id);
        log.info("抽取配置删除 {} {} by {}", id, c.getCode(), operator());
    }

    // ==================== 批次执行（BR-4.10-12/13 + 就绪标志） ====================

    @Override
    public List<Map<String, Object>> batchRuns(LocalDate date) {
        List<BiExtractRun> runs = runsOf(date);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiExtractRun r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("batchDate", r.getBatchDate());
            m.put("batchNo", r.getBatchNo());
            m.put("configId", r.getConfigId());
            m.put("seq", r.getSeq());
            m.put("status", r.getStatus());
            m.put("rowCount", r.getRowCount());
            m.put("prevRowCount", r.getPrevRowCount());
            m.put("watermark", r.getWatermark());
            m.put("retry", r.getRetry());
            m.put("errorMsg", r.getErrorMsg());
            m.put("manualConfirm", r.getManualConfirm());
            m.put("elapsedMs", r.getElapsedMs());
            m.put("finishedAt", r.getFinishedAt());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> runBatch(LocalDate date, boolean manual) {
        // 窗口语义（spec：窗口外不自动启动新批次，手动补跑除外）：
        // 仅拦截「窗口外 + 自动 + 新批次」；推进既有批次不受窗口限制（重试/挂起推进）
        BiExtractBatch existing = findBatch(date);
        if (existing == null && !manual && !inWindow(LocalTime.now())) {
            return Map.of("skipped", "outside-window", "date", date.toString());
        }
        BiExtractBatch batch = existing != null ? existing : ensureBatch(date);
        if (BiExtractBatch.ST_READY.equals(batch.getStatus())) {
            return batchView(batch, "already-ready");
        }
        List<BiExtractRun> runs = ensureRuns(date, batch.getBatchNo());
        if (runs.isEmpty()) {
            return batchView(batch, "no-enabled-config");
        }

        int executed = 0;
        boolean hold = false;
        for (BiExtractRun r : runs) {
            if (BiExtractRun.ST_READY.equals(r.getStatus())) {
                continue;
            }
            if (BiExtractRun.ST_PAUSED.equals(r.getStatus()) && !manual) {
                // 波动暂停：等人工确认（BR-4.10-12）
                hold = true;
                break;
            }
            if (BiExtractRun.ST_FAILED.equals(r.getStatus()) && r.getRetry() != null
                    && r.getRetry() >= retryMax && !manual) {
                // 重试耗尽转人工（BR-4.10-13）
                hold = true;
                break;
            }
            if (hold) {
                break;
            }
            if (upstreamNotReady(runs, r)) {
                break; // DAG：上游非 READY 挂起
            }
            boolean ok = execute(r, manual);
            executed++;
            if (!ok) {
                break;
            }
        }
        // 全 run READY → 质量联动 → 批次就绪/阻断（spec 就绪需求）
        Map<String, Object> out = batchView(batch, "advanced");
        out.put("executed", executed);
        recheckBatchStatus(batch);
        out.put("batchStatus", batch.getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirmRun(String runId) {
        BiExtractRun r = requireRun(runId);
        if (!BiExtractRun.ST_PAUSED.equals(r.getStatus())) {
            throw new ServiceException(422, "任务非暂停状态：" + r.getStatus());
        }
        r.setManualConfirm(true);
        r.setStatus(BiExtractRun.ST_PENDING);
        r.setErrorMsg(appendNote(r.getErrorMsg(), "已人工确认继续"));
        r.setUpdateBy(operator());
        runDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> abortRun(String runId) {
        BiExtractRun r = requireRun(runId);
        if (!BiExtractRun.ST_PAUSED.equals(r.getStatus())) {
            throw new ServiceException(422, "任务非暂停状态：" + r.getStatus());
        }
        r.setStatus(BiExtractRun.ST_FAILED);
        r.setErrorMsg(appendNote(r.getErrorMsg(), "人工放弃终止"));
        r.setFinishedAt(LocalDateTime.now());
        r.setUpdateBy(operator());
        runDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    @Override
    public Map<String, Object> readySnapshot() {
        BiExtractBatch latest = latestBatch();
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest == null) {
            out.put("ready", false);
            out.put("hint", "数据待就绪");
            return out;
        }
        out.put("ready", BiExtractBatch.ST_READY.equals(latest.getStatus()));
        out.put("batchNo", latest.getBatchNo());
        out.put("batchDate", latest.getBatchDate());
        out.put("status", latest.getStatus());
        out.put("readyAt", latest.getReadyAt());
        if (!BiExtractBatch.ST_READY.equals(latest.getStatus())) {
            out.put("hint", "数据待就绪");
        }
        return out;
    }

    /** cron 02:20（窗口内，先例 BgAuditSweepTask）：自动推进当日批次 */
    @Scheduled(cron = "0 20 2 * * ?")
    public void scheduledSweep() {
        try {
            runBatch(LocalDate.now(), false);
        } catch (Exception e) {
            log.warn("bi extract scheduled sweep failed: {}", e.getMessage());
        }
    }

    // ==================== 引擎内部 ====================

    /** 执行单 run：治理 SQL 计数 → 波动判定 → 水位推进 → READY/PAUSED/FAILED */
    private boolean execute(BiExtractRun r, boolean manual) {
        BiExtractConfig c = configDao.selectById(r.getConfigId());
        if (c == null) {
            return fail(r, "配置不存在：" + r.getConfigId(), manual);
        }
        BiDatasource ds = datasourceDao.selectById(c.getDatasourceId());
        if (ds == null || !BiDatasource.ST_ACTIVE.equals(ds.getStatus())) {
            // schema 变更暂停其下自动执行（BR-4.10-15）：挂起不推进
            r.setErrorMsg("数据源非 ACTIVE（结构已变更或停用），暂停执行");
            r.setUpdateBy(operator());
            runDao.updateById(r);
            return false;
        }
        BiReportTemplate tpl = templateDao.selectById(c.getTemplateId());
        if (tpl == null || !BiReportTemplate.ST_PUBLISHED.equals(tpl.getStatus())) {
            return fail(r, "模板非已发布状态，不可抽取", manual);
        }
        r.setStatus(BiExtractRun.ST_RUNNING);
        r.setUpdateBy(operator());
        runDao.updateById(r);
        long start = System.nanoTime();
        try {
            SqlGuard.validate(tpl.getSqlText());
            Long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (" + tpl.getSqlText() + ") __x", Long.class);
            long rowCount = rows == null ? 0L : rows;
            long elapsed = (System.nanoTime() - start) / 1_000_000L;

            // 波动判定（BR-4.10-12）：> 前次同配置 READY 行数 × N → 暂停待确认
            Long prev = prevReadyRows(c.getId(), r.getBatchDate());
            r.setPrevRowCount(prev);
            r.setRowCount(rowCount);
            if (!Boolean.TRUE.equals(r.getManualConfirm())
                    && prev != null && prev > 0 && rowCount > prev * fluctuationX) {
                r.setStatus(BiExtractRun.ST_PAUSED);
                r.setErrorMsg("数据量波动超 " + fluctuationX + " 倍（前次 " + prev
                        + " → 本批 " + rowCount + "），已暂停待确认（BR-4.10-12）");
                r.setElapsedMs(elapsed);
                r.setUpdateBy(operator());
                runDao.updateById(r);
                notifyRole("ROLE_DATA_GOV", "DataExtractFluctuation",
                        "抽取波动暂停：" + c.getName(),
                        r.getErrorMsg() + "，请人工确认继续或放弃");
                log.warn("extract paused by fluctuation {} rows={} prev={}", c.getId(), rowCount, prev);
                return false;
            }
            // 增量水位推进（MAX over 治理查询，不改写 SQL）
            String watermark = null;
            if (BiExtractConfig.MODE_INCREMENTAL.equals(c.getMode())
                    && StringUtils.hasText(c.getWatermarkField())) {
                String wf = c.getWatermarkField();
                if (!IDENT.matcher(wf).matches()) {
                    throw new ServiceException(422, "水位字段非法：" + wf);
                }
                Object wm = jdbc.queryForObject("SELECT MAX(__w." + wf + ") FROM ("
                        + tpl.getSqlText() + ") __w", Object.class);
                watermark = wm == null ? null : String.valueOf(wm);
                c.setLastWatermark(watermark);
                c.setUpdateBy(operator());
                configDao.updateById(c);
            }
            r.setStatus(BiExtractRun.ST_READY);
            r.setWatermark(watermark);
            r.setErrorMsg(null);
            r.setElapsedMs(elapsed);
            r.setFinishedAt(LocalDateTime.now());
            r.setUpdateBy(operator());
            runDao.updateById(r);
            return true;
        } catch (Exception e) {
            return fail(r, e.getMessage(), manual);
        }
    }

    /** 失败：RETRY+1、断点保留（水位不动）、达上限 FAILED+告警（BR-4.10-13） */
    private boolean fail(BiExtractRun r, String msg, boolean manual) {
        int retry = (r.getRetry() == null ? 0 : r.getRetry()) + 1;
        r.setRetry(retry);
        r.setErrorMsg(msg == null ? "执行异常" : msg.substring(0, Math.min(480, msg.length())));
        if (retry >= retryMax) {
            r.setStatus(BiExtractRun.ST_FAILED);
            r.setFinishedAt(LocalDateTime.now());
            notifyRole("ROLE_DATA_GOV", "DataExtractFailed",
                    "抽取任务失败（重试耗尽）：" + r.getBatchNo(),
                    "配置 " + r.getConfigId() + " 连续失败 " + retry + " 次已终止，等待人工补跑："
                            + r.getErrorMsg());
        } else {
            r.setStatus(BiExtractRun.ST_PENDING);
        }
        r.setUpdateBy(operator());
        runDao.updateById(r);
        log.warn("extract run failed {} (retry {}/{}): {}", r.getConfigId(), retry, retryMax, msg);
        return false;
    }

    /** 批次状态重算：全 READY → 质量联动 → READY / QUALITY_BLOCKED */
    private void recheckBatchStatus(BiExtractBatch batch) {
        List<BiExtractRun> runs = runsOf(batch.getBatchDate());
        for (BiExtractRun r : runs) {
            if (!BiExtractRun.ST_READY.equals(r.getStatus())) {
                return; // 仍有未完成任务
            }
        }
        List<String> dsIds = new ArrayList<>();
        for (BiExtractRun r : runs) {
            BiExtractConfig c = configDao.selectById(r.getConfigId());
            if (c != null && !dsIds.contains(c.getDatasourceId())) {
                dsIds.add(c.getDatasourceId());
            }
        }
        int severeFail = qualityCheckService.runForBatch(batch.getBatchNo(), dsIds);
        if (severeFail > 0) {
            batch.setStatus(BiExtractBatch.ST_QUALITY_BLOCKED);
            batch.setUpdateBy(operator());
            batchDao.updateById(batch);
            notifyRole("ROLE_DATA_GOV", "DataExtractQualityBlocked",
                    "批次质量阻断：" + batch.getBatchNo(),
                    "SEVERE 级质量校验失败 " + severeFail + " 条，批次不置数据就绪（BR-4.10-14），"
                            + "修复数据并复验通过后自动就绪");
        } else {
            batch.setStatus(BiExtractBatch.ST_READY);
            batch.setReadyAt(LocalDateTime.now());
            batch.setUpdateBy(operator());
            batchDao.updateById(batch);
        }
    }

    private boolean upstreamNotReady(List<BiExtractRun> runs, BiExtractRun r) {
        for (BiExtractRun u : runs) {
            if (u.getSeq() != null && r.getSeq() != null && u.getSeq() < r.getSeq()
                    && !BiExtractRun.ST_READY.equals(u.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private Long prevReadyRows(String configId, LocalDate exclude) {
        List<BiExtractRun> list = runDao.selectList(new LambdaQueryWrapper<BiExtractRun>()
                .eq(BiExtractRun::getConfigId, configId)
                .eq(BiExtractRun::getStatus, BiExtractRun.ST_READY)
                .ne(BiExtractRun::getBatchDate, exclude)
                .orderByDesc(BiExtractRun::getBatchDate)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0).getRowCount();
    }

    private BiExtractBatch findBatch(LocalDate date) {
        List<BiExtractBatch> list = batchDao.selectList(new LambdaQueryWrapper<BiExtractBatch>()
                .eq(BiExtractBatch::getBatchDate, date).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private BiExtractBatch ensureBatch(LocalDate date) {
        BiExtractBatch existing = findBatch(date);
        if (existing != null) {
            return existing;
        }
        BiExtractBatch b = new BiExtractBatch();
        b.setId(uuid());
        b.setBatchNo(date.toString().replace("-", ""));
        b.setBatchDate(date);
        b.setStatus(BiExtractBatch.ST_RUNNING);
        b.setCreateBy(operator());
        batchDao.insert(b);
        return b;
    }

    /** 生成当日 runs：仅 ACTIVE 数据源的 ENABLED 配置；幂等（已存在则复用） */
    private List<BiExtractRun> runsOf(LocalDate date) {
        return runDao.selectList(new LambdaQueryWrapper<BiExtractRun>()
                .eq(BiExtractRun::getBatchDate, date)
                .orderByAsc(BiExtractRun::getSeq));
    }

    private List<BiExtractRun> ensureRuns(LocalDate date, String batchNo) {
        List<BiExtractRun> existing = runsOf(date);
        if (!existing.isEmpty()) {
            return existing;
        }
        List<BiExtractConfig> configs = configDao.selectList(new LambdaQueryWrapper<BiExtractConfig>()
                .eq(BiExtractConfig::getStatus, BiExtractConfig.ST_ENABLED)
                .orderByAsc(BiExtractConfig::getSeq));
        for (BiExtractConfig c : configs) {
            BiDatasource ds = datasourceDao.selectById(c.getDatasourceId());
            if (ds == null || !BiDatasource.ST_ACTIVE.equals(ds.getStatus())) {
                continue; // schema 变更数据源不进批次（BR-4.10-15）
            }
            BiExtractRun r = new BiExtractRun();
            r.setId(uuid());
            r.setBatchDate(date);
            r.setBatchNo(batchNo);
            r.setConfigId(c.getId());
            r.setSeq(c.getSeq());
            r.setStatus(BiExtractRun.ST_PENDING);
            r.setRetry(0);
            r.setManualConfirm(false);
            r.setCreateBy(operator());
            runDao.insert(r);
        }
        return runsOf(date);
    }

    private BiExtractBatch latestBatch() {
        List<BiExtractBatch> list = batchDao.selectList(new LambdaQueryWrapper<BiExtractBatch>()
                .orderByDesc(BiExtractBatch::getBatchDate).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> batchView(BiExtractBatch b, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchNo", b.getBatchNo());
        m.put("batchDate", b.getBatchDate());
        m.put("status", b.getStatus());
        m.put("note", note);
        m.put("runs", batchRuns(b.getBatchDate()));
        return m;
    }

    // ==================== schema 快照 ====================

    private void captureSnapshot(BiDatasource d) {
        Map<String, Object> snap = currentSnapshot();
        d.setSchemaSnapshot(json(snap));
        d.setSnapshotAt(LocalDateTime.now());
    }

    /** 当前库全表列清单快照 {tables:{t:[{name,type}...]}} */
    private Map<String, Object> currentSnapshot() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TABLE_NAME AS t, COLUMN_NAME AS c, COLUMN_TYPE AS ty "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION");
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String t = String.valueOf(row.get("t"));
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", row.get("c"));
            col.put("type", row.get("ty"));
            tables.computeIfAbsent(t, k -> new ArrayList<>()).add(col);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        return out;
    }

    /** 快照对比：返回差异描述清单（列增/删/类型变） */
    @SuppressWarnings("unchecked")
    private List<String> diffSnapshot(Map<String, Object> stored, Map<String, Object> current) {
        List<String> diff = new ArrayList<>();
        Map<String, List<Map<String, Object>>> cur =
                (Map<String, List<Map<String, Object>>>) current.get("tables");
        Map<String, List<Map<String, Object>>> old =
                stored.get("tables") == null ? Map.of()
                        : (Map<String, List<Map<String, Object>>>) stored.get("tables");
        for (Map.Entry<String, List<Map<String, Object>>> e : cur.entrySet()) {
            List<Map<String, Object>> oldCols = old.get(e.getKey());
            if (oldCols == null) {
                diff.add("新增表 " + e.getKey());
                continue;
            }
            Map<String, String> oldMap = new LinkedHashMap<>();
            for (Map<String, Object> oc : oldCols) {
                oldMap.put(String.valueOf(oc.get("name")), String.valueOf(oc.get("type")));
            }
            for (Map<String, Object> nc : e.getValue()) {
                String n = String.valueOf(nc.get("name"));
                String ty = String.valueOf(nc.get("type"));
                if (!oldMap.containsKey(n)) {
                    diff.add(e.getKey() + "." + n + " 新增列");
                } else if (!oldMap.get(n).equals(ty)) {
                    diff.add(e.getKey() + "." + n + " 类型变更 " + oldMap.get(n) + "→" + ty);
                }
            }
        }
        for (String t : old.keySet()) {
            if (!cur.containsKey(t)) {
                diff.add("删除表 " + t);
            }
        }
        return diff;
    }

    // ==================== helpers ====================

    private void validateWatermark(BiReportTemplate tpl, String mode, String wmField) {
        if (!BiExtractConfig.MODE_FULL.equals(mode)
                && !BiExtractConfig.MODE_INCREMENTAL.equals(mode)) {
            throw new ServiceException(422, "抽取策略仅 FULL/INCREMENTAL");
        }
        if (BiExtractConfig.MODE_INCREMENTAL.equals(mode)) {
            if (!StringUtils.hasText(wmField) || !IDENT.matcher(wmField).matches()) {
                throw new ServiceException(422, "增量模式须登记合法水位字段");
            }
            // 水位字段须在模板列清单内（design D2 风险缓解）
            List<Map<String, Object>> cols = parseJson(tpl.getColumnsJson());
            if (!cols.isEmpty()) {
                boolean found = cols.stream().anyMatch(col ->
                        wmField.equalsIgnoreCase(str(col.get("field"))));
                if (!found) {
                    throw new ServiceException(422,
                            "水位字段不在模板列清单内：" + wmField);
                }
            }
        }
    }

    private BiReportTemplate requirePublishedTemplate(String tplId) {
        BiReportTemplate t = templateDao.selectById(tplId);
        if (t == null) {
            throw new ServiceException(404, "报表模板不存在：" + tplId);
        }
        if (!BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅已发布模板可绑定抽取配置（当前：" + t.getStatus() + "）");
        }
        return t;
    }

    private BiDatasource requireDs(String id) {
        BiDatasource d = datasourceDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "数据源不存在：" + id);
        }
        return d;
    }

    private BiExtractConfig requireConfig(String id) {
        BiExtractConfig c = configDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "抽取配置不存在：" + id);
        }
        return c;
    }

    private BiExtractRun requireRun(String id) {
        BiExtractRun r = runDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "批次任务不存在：" + id);
        }
        return r;
    }

    private Map<String, Object> dsView(BiDatasource d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("type", d.getType());
        m.put("dbName", d.getDbName());
        // 连接信息脱敏展示（明文不出接口，FR-4.10-2-1）
        m.put("connMasked", d.getConnEnc() == null ? null : "****(已加密)");
        m.put("status", d.getStatus());
        m.put("snapshotAt", d.getSnapshotAt());
        m.put("remark", d.getRemark());
        return m;
    }

    /** 角色广播告警（erp_sys_notice TARGET_ROLE，14.1 双条模式同列） */
    private void notifyRole(String role, String bizType, String title, String content) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, ?, ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    uuid(), role, bizType, uuid(), title,
                    content == null ? "" : content.substring(0, Math.min(900, content.length())));
        } catch (Exception e) {
            log.warn("extract notify failed: {}", e.getMessage());
        }
    }

    private static boolean inWindow(LocalTime t) {
        return !t.isBefore(WIN_FROM) && t.isBefore(WIN_TO);
    }

    private static String appendNote(String base, String note) {
        return (base == null ? "" : base + "（") + note + (base == null ? "" : "）");
    }

    private List<Map<String, Object>> parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
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
            String u = com.erp.util.SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}

package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.common.SqlGuard;
import com.erp.dao.bi.BiKpiAlertDao;
import com.erp.dao.bi.BiKpiDefDao;
import com.erp.dao.bi.BiKpiSnapshotDao;
import com.erp.dao.bi.BiKpiTargetDao;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.dao.bi.BiReportTemplateDao;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiReportTemplate;
import com.erp.entity.bi.KpiAlert;
import com.erp.entity.bi.KpiDef;
import com.erp.entity.bi.KpiSnapshot;
import com.erp.entity.bi.KpiTarget;
import com.erp.security.IntfGuard;
import com.erp.service.SysParamService;
import com.erp.service.bi.KpiService;
import com.erp.service.bi.QueryGovernance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 经营看板实现（add-kpi-dashboard，spec bi-kpi-dashboard）：
 * KPI 定义/目标维护、每日快照（取数 SQL 治理执行）、达成三色、趋势、预警生命周期。
 */
@Slf4j
@Service
public class KpiServiceImpl implements KpiService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BiKpiDefDao defDao;
    private final BiKpiTargetDao targetDao;
    private final BiKpiSnapshotDao snapshotDao;
    private final BiKpiAlertDao alertDao;
    private final BiMetricDictDao metricDictDao;
    private final BiReportTemplateDao templateDao;
    private final QueryGovernance governance;
    private final SysParamService sysParam;
    private final JdbcTemplate jdbc;

    public KpiServiceImpl(BiKpiDefDao defDao, BiKpiTargetDao targetDao,
                          BiKpiSnapshotDao snapshotDao, BiKpiAlertDao alertDao,
                          BiMetricDictDao metricDictDao, BiReportTemplateDao templateDao,
                          QueryGovernance governance, SysParamService sysParam,
                          JdbcTemplate jdbc) {
        this.defDao = defDao;
        this.targetDao = targetDao;
        this.snapshotDao = snapshotDao;
        this.alertDao = alertDao;
        this.metricDictDao = metricDictDao;
        this.templateDao = templateDao;
        this.governance = governance;
        this.sysParam = sysParam;
        this.jdbc = jdbc;
    }

    // ---------- KPI 定义（2.1） ----------

    @Override
    public List<Map<String, Object>> listDefs() {
        List<KpiDef> defs = defDao.selectList(new LambdaQueryWrapper<KpiDef>()
                .orderByAsc(KpiDef::getKpiKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KpiDef d : defs) {
            out.add(toDefRow(d));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveDef(Map<String, Object> payload) {
        KpiDef d = fromPayload(null, payload);
        d.setId(uuid());
        d.setCreateBy(IntfGuard.currentUser());
        defDao.insert(d);
        return toDefRow(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateDef(String id, Map<String, Object> payload) {
        KpiDef d = defDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "KPI 不存在");
        }
        KpiDef upd = fromPayload(id, payload);
        upd.setUpdateBy(IntfGuard.currentUser());
        defDao.updateById(upd);
        return toDefRow(defDao.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDef(String id) {
        KpiDef d = defDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "KPI 不存在");
        }
        defDao.deleteById(id);
    }

    /** 保存/更新共用校验：metric_key 已注册（C-4.10-01）/ SQL SELECT-only / 模板 PUBLISHED / 阈值缺省 */
    private KpiDef fromPayload(String id, Map<String, Object> p) {
        KpiDef d = new KpiDef();
        if (id != null) {
            d.setId(id);
        }
        String kpiKey = str(p.get("kpiKey"));
        String name = str(p.get("name"));
        String sqlText = str(p.get("sqlText"));
        if (kpiKey == null || kpiKey.isBlank() || name == null || name.isBlank()) {
            throw new ServiceException(422, "KPI 编码与名称必填");
        }
        if (sqlText == null || sqlText.isBlank()) {
            throw new ServiceException(422, "取数 SQL 必填");
        }
        d.setKpiKey(kpiKey);
        d.setName(name);
        d.setMetricKey(str(p.get("metricKey")));
        d.setDimKey(str(p.get("dimKey")));
        d.setSqlText(sqlText);
        d.setDrillTemplateId(str(p.get("drillTemplateId")));
        d.setTargetMode(str(p.get("targetMode")) == null ? KpiDef.MODE_PERIODIC
                : str(p.get("targetMode")));
        d.setFixedTarget(dec(p.get("fixedTarget")));
        d.setThresholdWarn(dec(p.get("thresholdWarn")) == null ? new BigDecimal("0.8")
                : dec(p.get("thresholdWarn")));
        d.setThresholdCritical(dec(p.get("thresholdCritical")) == null ? new BigDecimal("0.5")
                : dec(p.get("thresholdCritical")));
        d.setStatus(str(p.get("status")) == null ? KpiDef.ST_ENABLED : str(p.get("status")));
        d.setOwner(str(p.get("owner")));
        d.setRemark(str(p.get("remark")));

        // 口径引用：METRIC_KEY 非空须已注册（未注册 422，spec 场景）
        if (d.getMetricKey() != null && !d.getMetricKey().isBlank()) {
            BiMetricDict m = metricDictDao.selectCurrent(d.getMetricKey());
            if (m == null) {
                throw new ServiceException(422, "指标 " + d.getMetricKey()
                        + " 未注册口径，请先完成口径字典录入（C-4.10-01）");
            }
        }
        // 取数 SQL SELECT-only（spec 场景）
        SqlGuard.validate(d.getSqlText());
        // 下钻模板须 PUBLISHED
        if (d.getDrillTemplateId() != null && !d.getDrillTemplateId().isBlank()) {
            BiReportTemplate t = templateDao.selectById(d.getDrillTemplateId());
            if (t == null || !BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
                throw new ServiceException(422, "下钻模板须为已发布模板");
            }
        }
        // KPI_KEY 唯一（新建查重）
        if (id == null) {
            Long cnt = defDao.selectCount(new LambdaQueryWrapper<KpiDef>()
                    .eq(KpiDef::getKpiKey, kpiKey));
            if (cnt != null && cnt > 0) {
                throw new ServiceException(422, "KPI 编码已存在：" + kpiKey);
            }
        }
        return d;
    }

    // ---------- 期间目标（2.2） ----------

    @Override
    public List<Map<String, Object>> listTargets(String kpiKey) {
        List<KpiTarget> list = targetDao.selectList(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getKpiKey, kpiKey)
                .orderByDesc(KpiTarget::getPeriod));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KpiTarget t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("kpiKey", t.getKpiKey());
            m.put("period", t.getPeriod());
            m.put("targetValue", t.getTargetValue());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveTarget(Map<String, Object> payload) {
        String kpiKey = str(payload.get("kpiKey"));
        String period = str(payload.get("period"));
        BigDecimal value = dec(payload.get("targetValue"));
        if (kpiKey == null || period == null || value == null) {
            throw new ServiceException(422, "KPI/期间/目标值必填");
        }
        // upsert（UNIQUE KPI_KEY+PERIOD）
        KpiTarget existing = targetDao.selectOne(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getKpiKey, kpiKey).eq(KpiTarget::getPeriod, period)
                .last("LIMIT 1"));
        KpiTarget t;
        if (existing != null) {
            t = existing;
            t.setTargetValue(value);
            t.setUpdateBy(IntfGuard.currentUser());
            targetDao.updateById(t);
        } else {
            t = new KpiTarget();
            t.setId(uuid());
            t.setKpiKey(kpiKey);
            t.setPeriod(period);
            t.setTargetValue(value);
            t.setCreateBy(IntfGuard.currentUser());
            targetDao.insert(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("kpiKey", kpiKey);
        out.put("period", period);
        out.put("targetValue", value);
        return out;
    }

    // ---------- 快照计算（3.1） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runSnapshot() {
        List<KpiDef> defs = defDao.selectList(new LambdaQueryWrapper<KpiDef>()
                .eq(KpiDef::getStatus, KpiDef.ST_ENABLED));
        LocalDate today = LocalDate.now();
        String period = today.format(YM);
        int ok = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        for (KpiDef d : defs) {
            try {
                snapshotOne(d, today, period);
                ok++;
            } catch (Exception e) {
                fail++;
                String msg = d.getKpiKey() + ": " + e.getMessage();
                errors.add(msg.length() > 480 ? msg.substring(0, 480) : msg);
                // 失败留痕（spec：单 KPI 失败不阻断，记录失败原因）
                recordFailure(d, today, e.getMessage());
                log.warn("kpi snapshot {} failed: {}", d.getKpiKey(), e.getMessage());
            }
        }
        // 预警扫描（D4）
        int alerts = scanAlerts(today);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", defs.size());
        out.put("success", ok);
        out.put("failed", fail);
        out.put("errors", errors);
        out.put("alerts", alerts);
        out.put("snapDate", today.toString());
        return out;
    }

    /** 单 KPI：治理执行取数 SQL → 契约四列解析 → 目标绑定 → 快照 upsert */
    private void snapshotOne(KpiDef d, LocalDate today, String period) {
        SqlGuard.validate(d.getSqlText());
        // 执行身份 = KPI OWNER（快照任务无 SecurityContext，沿 worker asUser 先例）
        String asUser = d.getOwner();
        if (asUser == null || asUser.isBlank()) {
            throw new ServiceException(422, "KPI 未配置责任人，无法执行快照");
        }
        List<String> allowed = governance.allowedEntities(asUser);
        if (allowed != null && allowed.isEmpty()) {
            throw new ServiceException(403, "责任人行级权限为空集，快照被拒绝");
        }
        int timeout = sysParam.getInt("KPI_SNAPSHOT_TIMEOUT", 120);
        List<Map<String, Object>> rows = executeWithTimeout(d.getSqlText(), timeout);
        // 契约四列：kpi_key/dim_value/period_date/actual；同维度值求和合并
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String dim = str(r.get("dim_value"));
            dim = dim == null ? "" : dim;
            Object a = r.get("actual");
            BigDecimal actual = dec(a);
            if (actual == null) {
                continue;
            }
            merged.merge(dim, actual, BigDecimal::add);
        }
        if (merged.isEmpty()) {
            merged.put("", BigDecimal.ZERO); // 无行也落一行（全局 0）
        }
        BigDecimal target = resolveTarget(d, period);
        for (Map.Entry<String, BigDecimal> e : merged.entrySet()) {
            upsertSnapshot(d.getKpiKey(), e.getKey(), today, e.getValue(), target, d);
        }
    }

    /** 目标绑定：FIXED 取定义值；PERIODIC 取当期目标行；无目标 null */
    private BigDecimal resolveTarget(KpiDef d, String period) {
        if (KpiDef.MODE_FIXED.equals(d.getTargetMode())) {
            return d.getFixedTarget();
        }
        KpiTarget t = targetDao.selectOne(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getKpiKey, d.getKpiKey())
                .eq(KpiTarget::getPeriod, period).last("LIMIT 1"));
        return t == null ? null : t.getTargetValue();
    }

    /** 快照 upsert（UNIQUE KPI_KEY+DIM_VALUE+SNAP_DATE 幂等覆盖）+ 三色判定 */
    private void upsertSnapshot(String kpiKey, String dimValue, LocalDate snapDate,
                                BigDecimal actual, BigDecimal target, KpiDef d) {
        BigDecimal ratio = null;
        String status = null;
        if (target != null && target.signum() != 0) {
            ratio = actual.divide(target, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(d.getThresholdWarn()) >= 0) {
                status = KpiSnapshot.ST_ACHIEVED;
            } else if (ratio.compareTo(d.getThresholdCritical()) >= 0) {
                status = KpiSnapshot.ST_WARN;
            } else {
                status = KpiSnapshot.ST_BELOW;
            }
        }
        KpiSnapshot existing = snapshotDao.selectOne(new LambdaQueryWrapper<KpiSnapshot>()
                .eq(KpiSnapshot::getKpiKey, kpiKey)
                .eq(KpiSnapshot::getDimValue, dimValue)
                .eq(KpiSnapshot::getSnapDate, snapDate).last("LIMIT 1"));
        KpiSnapshot s;
        if (existing != null) {
            s = existing;
        } else {
            s = new KpiSnapshot();
            s.setId(uuid());
            s.setKpiKey(kpiKey);
            s.setDimValue(dimValue);
            s.setSnapDate(snapDate);
            s.setCreateBy("kpi-scheduler");
        }
        s.setActual(actual);
        s.setTarget(target);
        s.setRatio(ratio);
        s.setStatus(status);
        s.setErrorMsg(null);
        s.setUpdateBy("kpi-scheduler");
        if (existing != null) {
            snapshotDao.updateById(s);
        } else {
            snapshotDao.insert(s);
        }
    }

    /** 失败留痕（当日该 KPI 的全局行记 ERROR_MSG） */
    private void recordFailure(KpiDef d, LocalDate today, String msg) {
        KpiSnapshot existing = snapshotDao.selectOne(new LambdaQueryWrapper<KpiSnapshot>()
                .eq(KpiSnapshot::getKpiKey, d.getKpiKey())
                .eq(KpiSnapshot::getDimValue, "")
                .eq(KpiSnapshot::getSnapDate, today).last("LIMIT 1"));
        KpiSnapshot s;
        if (existing != null) {
            s = existing;
        } else {
            s = new KpiSnapshot();
            s.setId(uuid());
            s.setKpiKey(d.getKpiKey());
            s.setDimValue("");
            s.setSnapDate(today);
            s.setCreateBy("kpi-scheduler");
        }
        s.setErrorMsg(msg == null ? "执行失败" : (msg.length() > 480 ? msg.substring(0, 480) : msg));
        s.setUpdateBy("kpi-scheduler");
        if (existing != null) {
            snapshotDao.updateById(s);
        } else {
            snapshotDao.insert(s);
        }
    }

    private List<Map<String, Object>> executeWithTimeout(String sql, int timeoutSec) {
        return jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setQueryTimeout(timeoutSec);
                try (ResultSet rs = ps.executeQuery()) {
                    int n = rs.getMetaData().getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= n; c++) {
                            row.put(rs.getMetaData().getColumnLabel(c).toLowerCase(),
                                    rs.getObject(c));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    // ---------- 预警扫描（3.3 / D4） ----------

    /** 对每个带目标的 KPI×DIM_VALUE 扫描连续 N 天；返回处理预警数 */
    private int scanAlerts(LocalDate today) {
        int n = sysParam.getInt("KPI_ALERT_DAYS", 3);
        List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT KPI_KEY, DIM_VALUE FROM erp_bi_kpi_snapshot "
                        + "WHERE SNAP_DATE = ? AND TARGET IS NOT NULL AND DEL_FLAG='0' "
                        + "GROUP BY KPI_KEY, DIM_VALUE", today);
        int handled = 0;
        for (Map<String, Object> g : groups) {
            String kpiKey = str(g.get("KPI_KEY"));
            String dimValue = str(g.get("DIM_VALUE"));
            dimValue = dimValue == null ? "" : dimValue;
            try {
                scanOne(kpiKey, dimValue, today, n);
                handled++;
            } catch (Exception e) {
                log.warn("kpi alert scan {}-{} failed: {}", kpiKey, dimValue, e.getMessage());
            }
        }
        return handled;
    }

    private void scanOne(String kpiKey, String dimValue, LocalDate today, int n) {
        // 近 N 日快照（含今日）ratio 全 < 1 → 触发；今日 ratio ≥ 1 且 ACTIVE → 恢复
        List<Map<String, Object>> recent = jdbc.queryForList(
                "SELECT SNAP_DATE, RATIO FROM erp_bi_kpi_snapshot "
                        + "WHERE KPI_KEY=? AND DIM_VALUE=? AND DEL_FLAG='0' AND RATIO IS NOT NULL "
                        + "AND SNAP_DATE >= ? ORDER BY SNAP_DATE DESC LIMIT ?",
                kpiKey, dimValue, today.minusDays(n - 1), n);
        boolean allBreach = !recent.isEmpty() && recent.size() >= n
                && recent.stream().allMatch(r -> {
                    Object ratio = r.get("RATIO");
                    return ratio instanceof BigDecimal bd && bd.compareTo(BigDecimal.ONE) < 0;
                });
        Object todayRatio = recent.isEmpty() ? null : recent.get(0).get("RATIO");
        boolean todayOk = todayRatio instanceof BigDecimal bd && bd.compareTo(BigDecimal.ONE) >= 0;

        KpiAlert alert = alertDao.selectOne(new LambdaQueryWrapper<KpiAlert>()
                .eq(KpiAlert::getKpiKey, kpiKey).eq(KpiAlert::getDimValue, dimValue)
                .last("LIMIT 1"));

        if (todayOk) {
            // 回达标 → ACTIVE 置 RECOVERED
            if (alert != null && KpiAlert.ST_ACTIVE.equals(alert.getStatus())) {
                alert.setStatus(KpiAlert.ST_RECOVERED);
                alert.setRecoverDate(today);
                alert.setUpdateBy("kpi-scheduler");
                alertDao.updateById(alert);
            }
            return;
        }
        if (allBreach) {
            if (alert == null) {
                // 首次触发 → INSERT ACTIVE + 通知（防重）
                alert = new KpiAlert();
                alert.setId(uuid());
                alert.setKpiKey(kpiKey);
                alert.setDimValue(dimValue);
                alert.setStatus(KpiAlert.ST_ACTIVE);
                alert.setDaysBreached(n);
                // 最早 breach 日（近 N 日内最早）
                LocalDate earliest = recent.get(recent.size() - 1).get("SNAP_DATE") instanceof LocalDate ld
                        ? ld : today.minusDays(n - 1);
                alert.setFirstBreachDate(earliest);
                alert.setNotifyFlag(false);
                alert.setCreateBy("kpi-scheduler");
                alertDao.insert(alert);
            } else if (KpiAlert.ST_ACTIVE.equals(alert.getStatus())) {
                // 已 ACTIVE → 累加天数，不重复通知（spec：幂等）
                alert.setDaysBreached((alert.getDaysBreached() == null ? n : alert.getDaysBreached()) + 1);
                alert.setUpdateBy("kpi-scheduler");
                alertDao.updateById(alert);
            } else {
                // RECOVERED 后再次连续 breach → 重新激活
                alert.setStatus(KpiAlert.ST_ACTIVE);
                alert.setDaysBreached(n);
                alert.setRecoverDate(null);
                alert.setFirstBreachDate(today.minusDays(n - 1));
                alert.setUpdateBy("kpi-scheduler");
                alertDao.updateById(alert);
            }
        }
    }

    // ---------- 看板查询（4.1 / 4.2） ----------

    @Override
    public List<Map<String, Object>> summary() {
        LocalDate today = LocalDate.now();
        List<KpiDef> defs = defDao.selectList(new LambdaQueryWrapper<KpiDef>()
                .eq(KpiDef::getStatus, KpiDef.ST_ENABLED));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KpiDef d : defs) {
            // 取当日该 KPI 的 DIM_VALUE 分组快照（有维度 KPI 全部维度值；无维度仅全局行）
            List<KpiSnapshot> snaps = snapshotDao.selectList(new LambdaQueryWrapper<KpiSnapshot>()
                    .eq(KpiSnapshot::getKpiKey, d.getKpiKey())
                    .eq(KpiSnapshot::getSnapDate, today));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kpiKey", d.getKpiKey());
            m.put("name", d.getName());
            m.put("dimKey", d.getDimKey());
            m.put("thresholdWarn", d.getThresholdWarn());
            m.put("thresholdCritical", d.getThresholdCritical());
            m.put("drillTemplateId", d.getDrillTemplateId());
            m.put("hasDrill", d.getDrillTemplateId() != null && !d.getDrillTemplateId().isBlank());
            // 无维度 KPI：全局行；有维度：汇总列表（前端展开卡片或下钻）
            List<Map<String, Object>> dims = new ArrayList<>();
            boolean hasTarget = false;
            for (KpiSnapshot s : snaps) {
                if (s.getErrorMsg() != null) {
                    continue; // 失败留痕不进卡片
                }
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("dimValue", s.getDimValue());
                dm.put("actual", s.getActual());
                dm.put("target", s.getTarget());
                dm.put("ratio", s.getRatio());
                dm.put("status", s.getStatus());
                if (s.getTarget() != null) {
                    hasTarget = true;
                }
                dims.add(dm);
            }
            m.put("values", dims);
            m.put("hasTarget", hasTarget);
            // 主值（无维度取全局；有维度取首行或汇总）
            Map<String, Object> primary = dims.isEmpty() ? null : dims.get(0);
            m.put("actual", primary == null ? null : primary.get("actual"));
            m.put("target", primary == null ? null : primary.get("target"));
            m.put("ratio", primary == null ? null : primary.get("ratio"));
            m.put("status", primary == null ? null : primary.get("status"));
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> trend(String kpiKey, String dimValue, int days) {
        int limit = days > 0 ? days : sysParam.getInt("KPI_TREND_DAYS", 30);
        LocalDate today = LocalDate.now();
        String dim = dimValue == null ? "" : dimValue;
        List<KpiSnapshot> snaps = snapshotDao.selectList(new LambdaQueryWrapper<KpiSnapshot>()
                .eq(KpiSnapshot::getKpiKey, kpiKey)
                .eq(KpiSnapshot::getDimValue, dim)
                .ge(KpiSnapshot::getSnapDate, today.minusDays(limit))
                .le(KpiSnapshot::getSnapDate, today)
                .orderByAsc(KpiSnapshot::getSnapDate));
        List<Map<String, Object>> series = new ArrayList<>();
        BigDecimal target = null;
        for (KpiSnapshot s : snaps) {
            if (s.getErrorMsg() != null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", s.getSnapDate().toString());
            m.put("actual", s.getActual());
            m.put("ratio", s.getRatio());
            m.put("status", s.getStatus());
            series.add(m);
            if (s.getTarget() != null) {
                target = s.getTarget();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpiKey", kpiKey);
        out.put("dimValue", dim);
        out.put("target", target);
        out.put("series", series);
        return out;
    }

    @Override
    public List<Map<String, Object>> alerts(String status) {
        String st = status == null || status.isBlank() ? KpiAlert.ST_ACTIVE : status;
        List<KpiAlert> list = alertDao.selectList(new LambdaQueryWrapper<KpiAlert>()
                .eq(KpiAlert::getStatus, st)
                .orderByDesc(KpiAlert::getCreateDate));
        Map<String, String> kpiNames = new LinkedHashMap<>();
        for (KpiDef d : defDao.selectList(null)) {
            kpiNames.put(d.getKpiKey(), d.getName());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (KpiAlert a : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("kpiKey", a.getKpiKey());
            m.put("kpiName", kpiNames.getOrDefault(a.getKpiKey(), a.getKpiKey()));
            m.put("dimValue", a.getDimValue());
            m.put("status", a.getStatus());
            m.put("daysBreached", a.getDaysBreached());
            m.put("firstBreachDate", a.getFirstBreachDate() == null ? null
                    : a.getFirstBreachDate().toString());
            m.put("recoverDate", a.getRecoverDate() == null ? null
                    : a.getRecoverDate().toString());
            out.add(m);
        }
        return out;
    }

    // ---------- 内部 ----------

    private Map<String, Object> toDefRow(KpiDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("kpiKey", d.getKpiKey());
        m.put("name", d.getName());
        m.put("metricKey", d.getMetricKey());
        m.put("dimKey", d.getDimKey());
        m.put("sqlText", d.getSqlText());
        m.put("drillTemplateId", d.getDrillTemplateId());
        m.put("targetMode", d.getTargetMode());
        m.put("fixedTarget", d.getFixedTarget());
        m.put("thresholdWarn", d.getThresholdWarn());
        m.put("thresholdCritical", d.getThresholdCritical());
        m.put("status", d.getStatus());
        m.put("owner", d.getOwner());
        m.put("remark", d.getRemark());
        return m;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}

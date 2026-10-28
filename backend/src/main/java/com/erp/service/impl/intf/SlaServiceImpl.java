package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfDeliveryDao;
import com.erp.dao.intf.IntfSlaAlertDao;
import com.erp.dao.intf.IntfSlaMetricDao;
import com.erp.dao.intf.IntfSlaReportDao;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfSlaMetric;
import com.erp.entity.intf.IntfSlaReport;
import com.erp.security.IntfGuard;
import com.erp.service.intf.SlaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA 指标采集、三级告警升级与月报（spec interface-sla-monitoring，design D7）。
 * 采集窗口 = 上一分钟；数据源异常 → DATA_MISSING 且告警，禁止填 0（BR-4.9-30）。
 */
@Slf4j
@Service
public class SlaServiceImpl implements SlaService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter WIN_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final IntfCallLogDao callLogDao;
    private final IntfDeliveryDao deliveryDao;
    private final IntfSlaMetricDao metricDao;
    private final IntfSlaAlertDao alertDao;
    private final IntfSlaReportDao reportDao;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Value("${app.intf.sla-sync-threshold-minutes:5}") private int syncThresholdMinutes;
    @Value("${app.intf.sla-target-availability:99.9}") private double targetAvailability;
    @Value("${app.intf.escalation-response-minutes:30}") private int escalationMinutes;

    /** 错误率目标 < 0.1%（SOP-5.5-D 步骤1） */
    private static final BigDecimal ERR_TARGET = new BigDecimal("0.10");

    public SlaServiceImpl(IntfCallLogDao callLogDao, IntfDeliveryDao deliveryDao,
                          IntfSlaMetricDao metricDao, IntfSlaAlertDao alertDao,
                          IntfSlaReportDao reportDao) {
        this.callLogDao = callLogDao;
        this.deliveryDao = deliveryDao;
        this.metricDao = metricDao;
        this.alertDao = alertDao;
        this.reportDao = reportDao;
    }

    // ---------------------------------------------------------------- 采集

    @Override
    public int collect() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(1);
        int written = 0;
        String month = end.format(MONTH_FMT);

        // 1) 调用类指标：错误率 / 可用率 / P95
        try {
            int total = callLogDao.countAll(null, start);
            if (total > 0) {
                int failed = callLogDao.countAllFailed(null, start);
                Long serverErrLong = callLogDao.selectCount(new LambdaQueryWrapper<com.erp.entity.intf.IntfCallLog>()
                        .ge(com.erp.entity.intf.IntfCallLog::getCallAt, start)
                        .ge(com.erp.entity.intf.IntfCallLog::getRespCode, 500));
                int serverErr = serverErrLong == null ? 0 : serverErrLong.intValue();
                BigDecimal errRate = BigDecimal.valueOf(failed).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                written += writeMetric(IntfSlaMetric.KEY_ERROR_RATE, errRate, "%", ERR_TARGET,
                        errRate, start, end, month, total, lowerIsBetter(errRate, ERR_TARGET));

                BigDecimal avail = BigDecimal.valueOf(total - serverErr).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                BigDecimal target = BigDecimal.valueOf(targetAvailability);
                written += writeMetric(IntfSlaMetric.KEY_AVAILABILITY, avail, "%", target,
                        avail, start, end, month, total, higherIsBetter(avail, target));

                List<Integer> costs = callLogDao.selectCosts(start);
                if (!costs.isEmpty()) {
                    int p95 = costs.get((int) Math.ceil(costs.size() * 0.95) - 1);
                    BigDecimal targetMs = BigDecimal.valueOf(500);
                    written += writeMetric(IntfSlaMetric.KEY_P95, BigDecimal.valueOf(p95), "ms", targetMs,
                            BigDecimal.valueOf(p95), start, end, month, costs.size(), lowerIsBetter(BigDecimal.valueOf(p95), targetMs));
                }
            }
        } catch (Exception e) {
            log.warn("call metric collect failed: {}", e.getMessage());
            written += writeMissing(IntfSlaMetric.KEY_ERROR_RATE, start, end, month,
                    "调用审计数据源不可达：" + e.getMessage());
        }

        // 2) 端到端同步延迟（事件发生 → 消费方 ACK）
        try {
            Double secs = deliveryDao.selectAvgAckSeconds(start);
            if (secs != null) {
                BigDecimal minutes = BigDecimal.valueOf(secs).divide(BigDecimal.valueOf(60), 4,
                        RoundingMode.HALF_UP);
                BigDecimal threshold = BigDecimal.valueOf(syncThresholdMinutes);
                written += writeMetric(IntfSlaMetric.KEY_E2E_DELAY, minutes, "min", threshold,
                        minutes, start, end, month, deliveryDao.countDeliveredSince(start),
                        lowerIsBetter(minutes, threshold));
            }
        } catch (Exception e) {
            log.warn("e2e metric collect failed: {}", e.getMessage());
            written += writeMissing(IntfSlaMetric.KEY_E2E_DELAY, start, end, month,
                    "事件投递数据源不可达：" + e.getMessage());
        }

        // 3) 伙伴维度指标行（add-service-monitoring-console design D2）：
        //    活跃伙伴同口径写行（PARTNER_CODE 预留列），告警仍只挂全局行；零活动伙伴不写行
        written += collectPartnerRows(start, end, month);

        escalateOverdue();
        return written;
    }

    /** 窗口内活跃伙伴（call_log CALLER ∪ delivery PARTNER_CODE）各写一组指标行；单伙伴失败不中断其余 */
    private int collectPartnerRows(LocalDateTime start, LocalDateTime end, String month) {
        int written = 0;
        try {
            java.util.Set<String> partners = new java.util.LinkedHashSet<>();
            partners.addAll(callLogDao.selectActiveCallers(start));
            partners.addAll(deliveryDao.selectActivePartners(start));
            for (String partner : partners) {
                try {
                    int total = callLogDao.countAll(partner, start);
                    if (total > 0) {
                        int failed = callLogDao.countAllFailed(partner, start);
                        Long serverErrLong = callLogDao.selectCount(
                                new LambdaQueryWrapper<com.erp.entity.intf.IntfCallLog>()
                                        .eq(com.erp.entity.intf.IntfCallLog::getCaller, partner)
                                        .ge(com.erp.entity.intf.IntfCallLog::getCallAt, start)
                                        .ge(com.erp.entity.intf.IntfCallLog::getRespCode, 500));
                        int serverErr = serverErrLong == null ? 0 : serverErrLong.intValue();

                        BigDecimal errRate = BigDecimal.valueOf(failed).multiply(HUNDRED)
                                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                        written += writePartnerMetric(IntfSlaMetric.KEY_ERROR_RATE, errRate, "%",
                                ERR_TARGET, errRate, start, end, month, total,
                                lowerIsBetter(errRate, ERR_TARGET), partner);

                        BigDecimal avail = BigDecimal.valueOf(total - serverErr).multiply(HUNDRED)
                                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                        BigDecimal availTarget = BigDecimal.valueOf(targetAvailability);
                        written += writePartnerMetric(IntfSlaMetric.KEY_AVAILABILITY, avail, "%",
                                availTarget, avail, start, end, month, total,
                                higherIsBetter(avail, availTarget), partner);

                        List<Integer> costs = callLogDao.selectCostsForCaller(partner, start);
                        if (!costs.isEmpty()) {
                            int p95 = costs.get((int) Math.ceil(costs.size() * 0.95) - 1);
                            BigDecimal targetMs = BigDecimal.valueOf(500);
                            written += writePartnerMetric(IntfSlaMetric.KEY_P95,
                                    BigDecimal.valueOf(p95), "ms", targetMs,
                                    BigDecimal.valueOf(p95), start, end, month, costs.size(),
                                    lowerIsBetter(BigDecimal.valueOf(p95), targetMs), partner);
                        }
                    }
                    Double secs = deliveryDao.selectAvgAckSecondsForPartner(partner, start);
                    if (secs != null) {
                        BigDecimal minutes = BigDecimal.valueOf(secs)
                                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
                        BigDecimal threshold = BigDecimal.valueOf(syncThresholdMinutes);
                        written += writePartnerMetric(IntfSlaMetric.KEY_E2E_DELAY, minutes, "min",
                                threshold, minutes, start, end, month,
                                deliveryDao.countDeliveredForPartner(partner, start),
                                lowerIsBetter(minutes, threshold), partner);
                    }
                } catch (Exception one) {
                    log.debug("partner metric skipped partner={}: {}", partner, one.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("partner metric collect failed: {}", e.getMessage());
        }
        return written;
    }

    /** 写全局指标 + 越阈告警（返回 1 表示写入一条指标） */
    private int writeMetric(String key, BigDecimal value, String unit, BigDecimal target,
                            BigDecimal compare, LocalDateTime start, LocalDateTime end,
                            String month, int samples, String level) {
        return writeRow(key, value, unit, target, compare, start, end, month, samples, level,
                null, true);
    }

    /** 伙伴维度指标行（design D2）：PARTNER_CODE 落行，不触发告警（告警仅挂全局行防倍增） */
    private int writePartnerMetric(String key, BigDecimal value, String unit, BigDecimal target,
                                   BigDecimal compare, LocalDateTime start, LocalDateTime end,
                                   String month, int samples, String level, String partnerCode) {
        return writeRow(key, value, unit, target, compare, start, end, month, samples, level,
                partnerCode, false);
    }

    private int writeRow(String key, BigDecimal value, String unit, BigDecimal target,
                         BigDecimal compare, LocalDateTime start, LocalDateTime end,
                         String month, int samples, String level,
                         String partnerCode, boolean withAlert) {
        IntfSlaMetric m = new IntfSlaMetric();
        m.setMetricKey(key);
        m.setPartnerCode(partnerCode);
        m.setApiDomain("open-api");
        m.setWindowStart(start);
        m.setWindowEnd(end);
        m.setMetricValue(value);
        m.setUnit(unit);
        m.setTargetValue(target);
        m.setLevel(level);
        m.setDataStatus("OK");
        m.setSampleCount(samples);
        m.setMonthTag(month);
        metricDao.insert(m);

        if (withAlert && !IntfSlaMetric.LVL_OK.equals(level)) {
            recordAlert(key, level, value, target,
                    metricName(key) + " 实际 " + value + unit + "，目标 " + target + unit + "（" + level + "）");
        }
        return 1;
    }

    /** 数据源不可达：DATA_MISSING + 告警，禁止以 0 或上期值填充（BR-4.9-30） */
    private int writeMissing(String key, LocalDateTime start, LocalDateTime end, String month, String reason) {
        IntfSlaMetric m = new IntfSlaMetric();
        m.setMetricKey(key);
        m.setApiDomain("open-api");
        m.setWindowStart(start);
        m.setWindowEnd(end);
        m.setLevel(IntfSlaMetric.LVL_MISSING);
        m.setDataStatus("MISSING");
        m.setSampleCount(0);
        m.setMonthTag(month);
        m.setRemark(reason);
        metricDao.insert(m);
        recordAlert(key, IntfSlaAlert.LVL_WARNING, null, null,
                metricName(key) + " 数据缺失（" + reason + "），不参与达成率计算");
        return 1;
    }

    // ---------------------------------------------------------------- 告警与升级

    /** 三级告警 + 5 分钟窗口唯一键去重（BR-4.9-27）+ 连续 3 次 Critical 升级（BR-4.9-28） */
    private void recordAlert(String metricKey, String level, BigDecimal actual, BigDecimal threshold,
                             String message) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime window = now.withSecond(0).withNano(0).withMinute((now.getMinute() / 5) * 5);
        String alertKey = metricKey;

        IntfSlaAlert existing = alertDao.selectByKeyWindow(alertKey, window);
        if (existing != null) {
            // 同窗口已告警 → 聚合去重（BR-4.9-27），仅在级别升高时升级
            if (rank(level) > rank(existing.getLevel())) {
                existing.setLevel(level);
                existing.setActualValue(actual);
                existing.setMessage(message);
                alertDao.updateById(existing);
            }
            return;
        }

        IntfSlaAlert a = new IntfSlaAlert();
        a.setAlertKey(alertKey);
        a.setAlertWindow(window);
        a.setMetricKey(metricKey);
        a.setLevel(level);
        a.setActualValue(actual);
        a.setThresholdValue(threshold);
        a.setMessage(message);
        a.setStatus(IntfSlaAlert.ST_OPEN);
        a.setEscalationLevel(1);
        a.setAlertAt(now);

        boolean critical = IntfSlaAlert.LVL_CRITICAL.equals(level)
                || IntfSlaAlert.LVL_EMERGENCY.equals(level);
        if (critical) {
            int recent = alertDao.countCriticalWithin(alertKey, now.minusMinutes(15));
            a.setCritCount(recent);
            if (recent >= 3) {
                // 同指标连续 3 次 Critical → 升级接口运维主管并起 30 分钟计时
                a.setEscalationLevel(2);
                a.setEscalatedTo("接口运维主管");
                a.setResponseDueAt(now.plusMinutes(escalationMinutes));
                a.setMessage(message + "｜已自动升级至接口运维主管，" + escalationMinutes + " 分钟内须响应");
            }
        }
        alertDao.insert(a);
    }

    /** 超时未响应逐级升级（L2 → L3 供应链协同管理员） */
    private void escalateOverdue() {
        LocalDateTime now = LocalDateTime.now();
        for (IntfSlaAlert a : alertDao.selectList(new LambdaQueryWrapper<IntfSlaAlert>()
                .eq(IntfSlaAlert::getStatus, IntfSlaAlert.ST_OPEN)
                .eq(IntfSlaAlert::getEscalationLevel, 2)
                .isNull(IntfSlaAlert::getRespondedAt)
                .isNotNull(IntfSlaAlert::getResponseDueAt)
                .lt(IntfSlaAlert::getResponseDueAt, now))) {
            a.setEscalationLevel(3);
            a.setEscalatedTo("供应链协同管理员");
            a.setResponseDueAt(now.plusMinutes(escalationMinutes));
            a.setMessage(a.getMessage() + "｜升级响应超时，已逐级升级至供应链协同管理员");
            alertDao.updateById(a);
        }
    }

    private static int rank(String level) {
        if (IntfSlaAlert.LVL_EMERGENCY.equals(level)) {
            return 3;
        }
        if (IntfSlaAlert.LVL_CRITICAL.equals(level)) {
            return 2;
        }
        if (IntfSlaAlert.LVL_WARNING.equals(level)) {
            return 1;
        }
        return 0;
    }

    private static String lowerIsBetter(BigDecimal actual, BigDecimal target) {
        if (actual.compareTo(target) > 0) {
            return actual.compareTo(target.multiply(new BigDecimal("2"))) >= 0
                    ? IntfSlaMetric.LVL_EMERGENCY : IntfSlaMetric.LVL_CRITICAL;
        }
        return actual.compareTo(target.multiply(new BigDecimal("0.8"))) >= 0
                ? IntfSlaMetric.LVL_WARNING : IntfSlaMetric.LVL_OK;
    }

    private static String higherIsBetter(BigDecimal actual, BigDecimal target) {
        if (actual.compareTo(target) < 0) {
            return actual.compareTo(target.multiply(new BigDecimal("0.5"))) <= 0
                    ? IntfSlaMetric.LVL_EMERGENCY : IntfSlaMetric.LVL_CRITICAL;
        }
        return actual.compareTo(target.subtract(new BigDecimal("0.2"))) < 0
                ? IntfSlaMetric.LVL_WARNING : IntfSlaMetric.LVL_OK;
    }

    private static String metricName(String key) {
        switch (key) {
            case IntfSlaMetric.KEY_AVAILABILITY: return "API 可用率";
            case IntfSlaMetric.KEY_P95: return "P95 响应时间";
            case IntfSlaMetric.KEY_E2E_DELAY: return "端到端同步延迟";
            default: return "错误率";
        }
    }

    // ---------------------------------------------------------------- 月报

    @Override
    public Map<String, Object> generateReport(String monthTag, String operator) {
        String month = monthTag == null || monthTag.trim().isEmpty()
                ? LocalDateTime.now().minusDays(1).format(MONTH_FMT) : monthTag.trim();
        // 总览/未达标只统计全局行（PARTNER_CODE 为空）——伙伴行入章节，避免重复计数
        List<IntfSlaMetric> metrics = metricDao.selectList(new LambdaQueryWrapper<IntfSlaMetric>()
                .eq(IntfSlaMetric::getMonthTag, month)
                .isNull(IntfSlaMetric::getPartnerCode)
                .orderByAsc(IntfSlaMetric::getMetricKey));

        Map<String, Integer> overview = new LinkedHashMap<>();
        List<Map<String, Object>> unreach = new ArrayList<>();
        Map<String, Integer> countByLevel = new LinkedHashMap<>();
        for (IntfSlaMetric m : metrics) {
            countByLevel.merge(m.getLevel(), 1, Integer::sum);
            overview.merge(m.getMetricKey(), 1, Integer::sum);
            if (!IntfSlaMetric.LVL_OK.equals(m.getLevel())) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("metricKey", m.getMetricKey());
                u.put("actual", m.getMetricValue());
                u.put("target", m.getTargetValue());
                u.put("level", m.getLevel());
                u.put("windowStart", m.getWindowStart());
                u.put("rootCause", m.getRemark() == null ? "待补充根因归类" : m.getRemark());
                u.put("owner", "接口运维工程师");
                u.put("partnerCode", m.getPartnerCode());
                unreach.add(u);
            }
        }

        IntfSlaReport r = reportDao.selectByMonth(month);
        if (r != null && !IntfSlaReport.ST_GENERATED.equals(r.getStatus())) {
            throw new ServiceException(422, "该月报告状态为 " + r.getStatus() + "，不可重新生成（修订须升版本）");
        }
        if (r == null) {
            r = new IntfSlaReport();
            r.setMonthTag(month);
            r.setVersion("v1");
        }
        r.setTitle(month + " 月度接口 SLA 报告");
        r.setStatus(IntfSlaReport.ST_GENERATED);
        // design D1：METRICS_JSON 重组为 {overview, chapters[]}（报告表零历史行，形状变更零兼容成本）
        Map<String, Object> metricsPayload = new LinkedHashMap<>();
        metricsPayload.put("overview", overview);
        metricsPayload.put("chapters", buildChapters(month));
        r.setMetricsJson(toJson(metricsPayload));
        r.setUnreachJson(toJson(unreach));
        // 手动调用记实际操作人（调度线程无登录上下文 → 调度器显式传 "sla-job"）；
        // 互斥检查 generatedBy.equals(currentUser) 从此对人类生效（spec MODIFIED 月报 req）
        r.setGeneratedBy(operator == null || operator.trim().isEmpty() ? "sla-job" : operator.trim());
        r.setGeneratedAt(LocalDateTime.now());
        r.setReviewedBy(null);
        r.setReviewedAt(null);
        if (r.getId() == null) {
            reportDao.insert(r);
        } else {
            reportDao.updateById(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("monthTag", month);
        out.put("status", r.getStatus());
        out.put("metricCounts", countByLevel);
        out.put("unreachCount", unreach.size());
        out.put("generatedBy", r.getGeneratedBy());
        return out;
    }

    /** 章节 = 按伙伴聚合当月 PARTNER_CODE 指标行（design D1：METRICS_JSON={overview, chapters[]}） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildChapters(String month) {
        List<IntfSlaMetric> rows = metricDao.selectList(new LambdaQueryWrapper<IntfSlaMetric>()
                .eq(IntfSlaMetric::getMonthTag, month)
                .isNotNull(IntfSlaMetric::getPartnerCode)
                .orderByAsc(IntfSlaMetric::getPartnerCode, IntfSlaMetric::getMetricKey));
        Map<String, List<IntfSlaMetric>> byPartner = new LinkedHashMap<>();
        for (IntfSlaMetric m : rows) {
            byPartner.computeIfAbsent(m.getPartnerCode(), k -> new ArrayList<>()).add(m);
        }
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (Map.Entry<String, List<IntfSlaMetric>> e : byPartner.entrySet()) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("partnerCode", e.getKey());
            // 指标聚合：每 metricKey → count/avg/level 分布
            Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
            for (IntfSlaMetric m : e.getValue()) {
                Map<String, Object> a = agg.computeIfAbsent(m.getMetricKey(), k -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("count", 0);
                    x.put("valueSum", BigDecimal.ZERO);
                    x.put("levels", new LinkedHashMap<String, Integer>());
                    return x;
                });
                a.put("count", (Integer) a.get("count") + 1);
                if (m.getMetricValue() != null) {
                    a.put("valueSum", ((BigDecimal) a.get("valueSum")).add(m.getMetricValue()));
                }
                ((Map<String, Integer>) a.get("levels")).merge(m.getLevel(), 1, Integer::sum);
            }
            Map<String, Object> metricOut = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> ae : agg.entrySet()) {
                Map<String, Object> a = ae.getValue();
                int cnt = (Integer) a.get("count");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", cnt);
                out.put("avg", cnt == 0 ? null : ((BigDecimal) a.get("valueSum"))
                        .divide(BigDecimal.valueOf(cnt), 4, RoundingMode.HALF_UP));
                out.put("levels", a.get("levels"));
                metricOut.put(ae.getKey(), out);
            }
            ch.put("metrics", metricOut);
            // 该伙伴未达标子项（level≠OK）
            List<Map<String, Object>> unreach = new ArrayList<>();
            for (IntfSlaMetric m : e.getValue()) {
                if (!IntfSlaMetric.LVL_OK.equals(m.getLevel())) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("metricKey", m.getMetricKey());
                    u.put("actual", m.getMetricValue());
                    u.put("target", m.getTargetValue());
                    u.put("level", m.getLevel());
                    u.put("windowStart", m.getWindowStart());
                    u.put("partnerCode", m.getPartnerCode());
                    u.put("rootCause", m.getRemark() == null ? "待补充根因归类" : m.getRemark());
                    unreach.add(u);
                }
            }
            ch.put("unreach", unreach);
            chapters.add(ch);
        }
        return chapters;
    }

    @Override
    public Map<String, Object> reviewReport(String reportId, String opinion) {
        requireReviewer();
        IntfSlaReport r = requireReport(reportId);
        if (opinion == null || opinion.trim().length() < 2) {
            throw new ServiceException(400, "审核意见必填，不可留空（SOP-5.5-D 步骤4）");
        }
        if (r.getGeneratedBy() != null && r.getGeneratedBy().equals(IntfGuard.currentUser())) {
            // 审核人不得为报告生成作业的执行人
            throw new ServiceException(422, "审核人不得为报告生成作业的执行人");
        }
        if (IntfSlaReport.ST_ARCHIVED.equals(r.getStatus()) || IntfSlaReport.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(422, "已归档报告不可再审核，修订须升版本重新走审核归档");
        }
        r.setStatus(IntfSlaReport.ST_REVIEWED);
        r.setReviewedBy(IntfGuard.currentUser());
        r.setReviewedAt(LocalDateTime.now());
        r.setReviewOpinion(opinion);
        reportDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus(), "reviewedBy", r.getReviewedBy());
    }

    @Override
    public Map<String, Object> archiveReport(String reportId) {
        // 高危动作：服务端二次校验 ADMIN（design D5）
        IntfGuard.requireAdmin("SLA 报告归档");
        IntfSlaReport r = requireReport(reportId);
        if (!IntfSlaReport.ST_REVIEWED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅审核通过的报告可归档，当前状态 " + r.getStatus());
        }
        r.setStatus(IntfSlaReport.ST_ARCHIVED);
        r.setArchivedBy(IntfGuard.currentUser());
        r.setArchivedAt(LocalDateTime.now());
        if (r.getVersion() == null || r.getVersion().trim().isEmpty()) {
            r.setVersion("v1");
        }
        reportDao.updateById(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("status", r.getStatus());
        out.put("version", r.getVersion());
        out.put("archivedAt", r.getArchivedAt());
        return out;
    }

    @Override
    public Map<String, Object> publishReport(String reportId, String scope) {
        // 高危动作：服务端二次校验 ADMIN（design D5）
        IntfGuard.requireAdmin("SLA 报告对外发布");
        IntfSlaReport r = requireReport(reportId);
        if (!IntfSlaReport.ST_ARCHIVED.equals(r.getStatus())) {
            // C-5.5-04 / C-0-01：未归档禁止对外发布 + 记录违规尝试
            recordViolation(r);
            throw new ServiceException(422, "报告未归档，不可发布");
        }
        r.setStatus(IntfSlaReport.ST_PUBLISHED);
        r.setPublishAt(LocalDateTime.now());
        r.setPublishScope(scope);
        reportDao.updateById(r);
        return Map.of("id", r.getId(), "status", r.getStatus(), "publishAt", r.getPublishAt(),
                "scope", scope);
    }

    /** 审核角色 = 集成架构师（spec：报告 MUST 经集成架构师审核），ADMIN 沿全局惯例直通 */
    private void requireReviewer() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = IntfGuard.isAdmin() || auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTF_ARCH".equals(a.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "报告审核仅限集成架构师（ROLE_INTF_ARCH）或管理员");
        }
    }

    private void recordViolation(IntfSlaReport r) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime window = now.withSecond(0).withNano(0).withMinute((now.getMinute() / 5) * 5);
        if (alertDao.selectByKeyWindow("SLA_PUBLISH_BLOCK:" + r.getId(), window) != null) {
            return;
        }
        IntfSlaAlert a = new IntfSlaAlert();
        a.setAlertKey("SLA_PUBLISH_BLOCK:" + r.getId());
        a.setAlertWindow(window);
        a.setMetricKey("SLA_REPORT");
        a.setLevel(IntfSlaAlert.LVL_WARNING);
        a.setMessage("未归档报告被尝试对外发布（C-5.5-04），操作人 " + IntfGuard.currentUser());
        a.setStatus(IntfSlaAlert.ST_OPEN);
        a.setEscalationLevel(1);
        a.setAlertAt(now);
        alertDao.insert(a);
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public Page<IntfSlaMetric> metrics(long current, long size, String metricKey, String monthTag,
                                       String partnerCode) {
        LambdaQueryWrapper<IntfSlaMetric> qw = new LambdaQueryWrapper<>();
        qw.eq(metricKey != null && !metricKey.trim().isEmpty(), IntfSlaMetric::getMetricKey, metricKey == null ? "" : metricKey.trim());
        qw.eq(monthTag != null && !monthTag.trim().isEmpty(), IntfSlaMetric::getMonthTag, monthTag == null ? "" : monthTag.trim());
        // 伙伴维度筛选：null=全部；空串=全局行；值=该伙伴（design D2）
        if (partnerCode != null) {
            String p = partnerCode.trim();
            if (p.isEmpty()) {
                qw.isNull(IntfSlaMetric::getPartnerCode);
            } else {
                qw.eq(IntfSlaMetric::getPartnerCode, p);
            }
        }
        qw.orderByDesc(IntfSlaMetric::getWindowStart);
        return metricDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Page<IntfSlaAlert> alerts(long current, long size, String level, String status) {
        LambdaQueryWrapper<IntfSlaAlert> qw = new LambdaQueryWrapper<>();
        qw.eq(level != null && !level.trim().isEmpty(), IntfSlaAlert::getLevel, level == null ? "" : level.trim());
        qw.eq(status != null && !status.trim().isEmpty(), IntfSlaAlert::getStatus, status == null ? "" : status.trim());
        qw.orderByDesc(IntfSlaAlert::getAlertAt);
        return alertDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> respondAlert(String alertId) {
        IntfSlaAlert a = alertDao.selectById(alertId);
        if (a == null) {
            throw new ServiceException(404, "告警不存在");
        }
        a.setStatus(IntfSlaAlert.ST_ACK);
        a.setRespondedBy(IntfGuard.currentUser());
        a.setRespondedAt(LocalDateTime.now());
        alertDao.updateById(a);
        return Map.of("id", a.getId(), "status", a.getStatus(), "respondedBy", a.getRespondedBy(),
                "escalationLevel", a.getEscalationLevel());
    }

    @Override
    public Page<IntfSlaReport> reports(long current, long size, String status) {
        LambdaQueryWrapper<IntfSlaReport> qw = new LambdaQueryWrapper<>();
        qw.eq(status != null && !status.trim().isEmpty(), IntfSlaReport::getStatus, status == null ? "" : status.trim());
        qw.orderByDesc(IntfSlaReport::getMonthTag);
        return reportDao.selectPage(new Page<>(current, size), qw);
    }

    private IntfSlaReport requireReport(String id) {
        IntfSlaReport r = reportDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "SLA 报告不存在");
        }
        return r;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}

package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmCostAlertDao;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmCostAlert;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmCostVarianceService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 成本偏差监控与冻结实现（add-cost-collection-freeze，design D3）。
 * 双阈值口径（消解 FR/C-4.8-02 与 BR-4.8-21 矛盾）：
 * 偏差率 > COST_TOLERANCE（SysParam 默认 0.5%）→ 仅预警；
 * 实际累计成本 > 预算基线 → 自动冻结（FREEZE_FLAG）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmCostVarianceServiceImpl implements PmCostVarianceService {

    /** RED 阈值（%）：偏差率 > 此值为红色预警（沿 hours_alert 两级惯例） */
    private static final BigDecimal RED_RATE = new BigDecimal("10");

    private final PmProjectDao projectDao;
    private final PmBudgetLineDao budgetLineDao;
    private final PmCostCollectDao costCollectDao;
    private final PmCostAlertDao alertDao;
    private final com.erp.service.SysParamService sysParamService;
    private final NoticeService noticeService;
    private final PmCostCollectService costCollectService;

    @Override
    public Map<String, Object> variance(String projectId) {
        PmProject p = projectDao.selectById(projectId);
        if (p == null) {
            throw new ServiceException(422, "项目不存在");
        }
        BigDecimal baseline = baselineOf(projectId);
        BigDecimal pv = pvOf(p, baseline);
        Map<String, Object> byType = costCollectService.summaryByType(projectId);
        BigDecimal actual = (BigDecimal) byType.get("total");
        BigDecimal rate = deviationRate(pv, actual);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("projectName", p.getName());
        out.put("baseline", baseline);
        out.put("pv", pv);
        out.put("actual", actual);
        out.put("deviationRate", rate);
        out.put("frozen", PmProject.FREEZE_YES.equals(p.getFreezeFlag()));
        out.put("freezeReason", p.getFreezeReason());
        out.put("byType", byType);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> dailyScan() {
        BigDecimal tolerance = costTolerance();
        int frozen = 0;
        int yellow = 0;
        int red = 0;
        int skipped = 0;
        LocalDate today = LocalDate.now();
        for (PmProject p : projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE))) {
            if (PmProject.FREEZE_YES.equals(p.getFreezeFlag())) {
                skipped++; // 已冻结不重复通知
                continue;
            }
            BigDecimal baseline = baselineOf(p.getId());
            if (baseline.signum() <= 0) {
                continue; // 无基线不判定（未锁基线项目）
            }
            BigDecimal pv = pvOf(p, baseline);
            BigDecimal actual = actualOf(p.getId());
            // 判定顺序（BR-4.8-21）：先查超基线 → 冻结；否则查偏差超容差 → 预警
            if (actual.compareTo(baseline) > 0) {
                freeze(p, baseline, actual);
                frozen++;
                continue;
            }
            BigDecimal rate = deviationRate(pv, actual);
            if (rate != null && rate.compareTo(tolerance) > 0) {
                String level = rate.compareTo(RED_RATE) > 0 ? "RED" : "YELLOW";
                upsertAlert(p, level, rate, pv, actual, today);
                notifyAlert(p, level, rate);
                if ("RED".equals(level)) {
                    red++;
                } else {
                    yellow++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frozen", frozen);
        out.put("yellow", yellow);
        out.put("red", red);
        out.put("skipped", skipped);
        out.put("tolerance", tolerance);
        return out;
    }

    @Override
    public List<Map<String, Object>> listAlerts(String projectId, String handledFlag) {
        LambdaQueryWrapper<PmCostAlert> qw = new LambdaQueryWrapper<PmCostAlert>()
                .eq(PmCostAlert::getDelFlag, "0")
                .orderByDesc(PmCostAlert::getAlertDate);
        if (hasText(projectId)) {
            qw.eq(PmCostAlert::getProjectId, projectId);
        }
        if (hasText(handledFlag)) {
            qw.eq(PmCostAlert::getHandledFlag, handledFlag);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmCostAlert a : alertDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alert", a);
            PmProject p = projectDao.selectById(a.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> markHandled(String alertId, String note) {
        PmCostAlert a = alertDao.selectById(alertId);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(422, "预警不存在");
        }
        a.setHandledFlag("1");
        a.setHandledBy(currentUser());
        a.setHandledNote(note);
        a.setHandledAt(LocalDateTime.now());
        a.setUpdateDate(LocalDateTime.now());
        alertDao.updateById(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("handledFlag", a.getHandledFlag());
        return out;
    }

    // ---- private ----

    /** 冻结项目（BR-4.8-21）：写冻结三列 + 通知 PM/PMO */
    private void freeze(PmProject p, BigDecimal baseline, BigDecimal actual) {
        p.setFreezeFlag(PmProject.FREEZE_YES);
        p.setFreezeReason("实际累计成本 " + actual + " 元已超预算基线 " + baseline + " 元（超支 "
                + actual.subtract(baseline) + " 元），系统自动冻结后续支出");
        p.setFrozenAt(LocalDateTime.now());
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        try {
            noticeService.push(null, p.getPmUserId(),
                    "项目超支冻结：" + p.getName(),
                    "项目 " + nvl(p.getProjectNo()) + " 实际累计成本 " + actual
                            + " 元已超预算基线 " + baseline + " 元，系统已自动冻结后续支出（领料/外协付款/工时录入）。"
                            + "请分析超支原因并提交《超预算审批申请》。",
                    "PmCostFreeze", p.getId());
            noticeService.push("ROLE_PMO", null,
                    "项目超支冻结：" + p.getName(),
                    "项目 " + nvl(p.getProjectNo()) + " 实际累计 " + actual + " 元超基线 "
                            + baseline + " 元，已自动冻结，等待超预算审批。",
                    "PmCostFreeze", p.getId());
        } catch (Exception e) {
            log.warn("pm cost freeze notice failed: {}", e.getMessage());
        }
        log.info("pm project frozen: {} actual={} baseline={}", p.getId(), actual, baseline);
    }

    /** 预警 upsert（UK=PROJECT+LEVEL+DATE 幂等） */
    private void upsertAlert(PmProject p, String level, BigDecimal rate,
                             BigDecimal pv, BigDecimal actual, LocalDate today) {
        PmCostAlert a = alertDao.selectOne(new LambdaQueryWrapper<PmCostAlert>()
                .eq(PmCostAlert::getProjectId, p.getId())
                .eq(PmCostAlert::getLevel, level)
                .eq(PmCostAlert::getAlertDate, today)
                .last("LIMIT 1"));
        if (a == null) {
            a = new PmCostAlert();
            a.setId(uuid());
            a.setProjectId(p.getId());
            a.setLevel(level);
            a.setAlertDate(today);
            a.setHandledFlag("0");
            a.setCreateBy("system");
            a.setCreateDate(LocalDateTime.now());
        }
        a.setDeviationRate(rate);
        a.setPv(pv);
        a.setActual(actual);
        a.setUpdateDate(LocalDateTime.now());
        if (a.getId() != null && alertDao.selectById(a.getId()) != null) {
            alertDao.updateById(a);
        } else {
            alertDao.insert(a);
        }
    }

    private void notifyAlert(PmProject p, String level, BigDecimal rate) {
        try {
            noticeService.push(null, p.getPmUserId(),
                    ("RED".equals(level) ? "红色" : "黄色") + "成本偏差预警：" + p.getName(),
                    "项目 " + nvl(p.getProjectNo()) + " 成本偏差率 " + rate + "%（实际累计已超时间进度 PV，"
                            + "尚未超基线），请分析原因并制定节约/纠偏措施。阈值 COST_TOLERANCE 可调。",
                    "PmCostAlert", p.getId());
            if ("RED".equals(level)) {
                noticeService.push("ROLE_PMO", null,
                        "红色成本偏差预警：" + p.getName(),
                        "项目 " + nvl(p.getProjectNo()) + " 偏差率 " + rate + "%，已通知项目经理，请关注。",
                        "PmCostAlert", p.getId());
            }
        } catch (Exception e) {
            log.warn("pm cost alert notice failed: {}", e.getMessage());
        }
    }

    /** PV = 基线 × 时间进度（线性，clamp [0,1]） */
    private BigDecimal pvOf(PmProject p, BigDecimal baseline) {
        if (p.getStartDate() == null || p.getEndDate() == null) {
            return BigDecimal.ZERO;
        }
        long total = p.getEndDate().toEpochDay() - p.getStartDate().toEpochDay();
        if (total <= 0) {
            return baseline; // 起止同日/异常 → 视为完成
        }
        long elapsed = LocalDate.now().toEpochDay() - p.getStartDate().toEpochDay();
        BigDecimal progress;
        if (elapsed <= 0) {
            progress = BigDecimal.ZERO;
        } else if (elapsed >= total) {
            progress = BigDecimal.ONE;
        } else {
            progress = BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        }
        return baseline.multiply(progress).setScale(2, RoundingMode.HALF_UP);
    }

    /** 偏差率（%）：PV=0 时返回 null（不可算） */
    private BigDecimal deviationRate(BigDecimal pv, BigDecimal actual) {
        if (pv == null || pv.signum() <= 0) {
            return null;
        }
        return actual.subtract(pv).multiply(new BigDecimal("100"))
                .divide(pv, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal actualOf(String projectId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PmCostCollect c : costCollectDao.selectList(new LambdaQueryWrapper<PmCostCollect>()
                .eq(PmCostCollect::getProjectId, projectId)
                .eq(PmCostCollect::getDelFlag, "0"))) {
            sum = sum.add(c.getAmount());
        }
        return sum;
    }

    private BigDecimal baselineOf(String projectId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PmBudgetLine l : budgetLineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            sum = sum.add(nz(l.getLabor())).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                    .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
        }
        return sum;
    }

    /** COST_TOLERANCE（SysParam，默认 0.5%） */
    private BigDecimal costTolerance() {
        String v = sysParamService.getValue("COST_TOLERANCE", "0.5");
        try {
            return new BigDecimal(v.trim());
        } catch (Exception e) {
            return new BigDecimal("0.5");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String currentUser() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return "system";
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.pm.PmHoursAlertDao;
import com.erp.dao.pm.PmHoursCollectDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.pm.PmHoursAlert;
import com.erp.entity.pm.PmHoursCollect;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmHoursVarianceService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
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
 * 工时偏差与预警实现（add-timesheet-management；design D4：
 * 每日扫描分级预警 + 已处理标记收口 5 工作日升级 + 幂等双保险）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmHoursVarianceServiceImpl implements PmHoursVarianceService {

    /** 纠偏窗口：预警后 5 工作日未标记已处理 → 升级 PMO（FR-4.8-4-5） */
    private static final int ESCALATE_WORKDAYS = 5;

    private final PmHoursAlertDao alertDao;
    private final PmHoursCollectDao collectDao;
    private final PmWbsDao wbsDao;
    private final PmProjectDao projectDao;
    private final HrRemindDao remindDao;
    private final NoticeService noticeService;
    private final com.erp.service.SysParamService sysParamService;

    @Override
    public Map<String, Object> variance(String projectId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PmProject p : projects(projectId)) {
            rows.add(projectVariance(p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", rows);
        out.put("alerts", alertRows(projectId));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> handleAlert(String alertId, String note) {
        PmHoursAlert alert = alertDao.selectById(alertId);
        if (alert == null || "1".equals(alert.getDelFlag())) {
            throw new ServiceException(422, "预警记录不存在");
        }
        if (!hasAnyRole("PMO", "ADMIN", "PM")) {
            throw new ServiceException(403, "仅项目经理/PMO 可标记已处理");
        }
        if ("1".equals(alert.getHandledFlag())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", alert.getId());
            out.put("handled", true);
            out.put("note", "已处理过，幂等返回");
            return out;
        }
        alert.setHandledFlag("1");
        alert.setHandledBy(SecurityUtils.getCurrentUserId());
        alert.setHandledNote(note);
        alert.setHandledAt(LocalDateTime.now());
        alert.setUpdateBy(SecurityUtils.getCurrentUserId());
        alert.setUpdateDate(LocalDateTime.now());
        alertDao.updateById(alert);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", alert.getId());
        out.put("handled", true);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> dailyScan() {
        int yellow = 0;
        int red = 0;
        int escalated = 0;
        LocalDate today = LocalDate.now();
        for (PmProject p : projects(null)) {
            BigDecimal deviation = deviationRateOf(p.getId());
            if (deviation == null) {
                continue; // 分母 0（无预估工时）跳过
            }
            BigDecimal yellowTh = BigDecimal.valueOf(sysParamService.getInt("HOURS_TOLERANCE_YELLOW", 10));
            BigDecimal redTh = BigDecimal.valueOf(sysParamService.getInt("HOURS_TOLERANCE_RED", 20));
            if (deviation.compareTo(redTh) > 0) {
                if (ensureAlert(p, PmHoursAlert.LV_RED, deviation, today)) {
                    notifyAlert(p, PmHoursAlert.LV_RED, deviation, true);
                    red++;
                }
            } else if (deviation.compareTo(yellowTh) > 0) {
                if (ensureAlert(p, PmHoursAlert.LV_YELLOW, deviation, today)) {
                    notifyAlert(p, PmHoursAlert.LV_YELLOW, deviation, false);
                    yellow++;
                }
            }
        }
        // 纠偏升级：未处理且超 5 工作日
        for (PmHoursAlert alert : alertDao.selectList(new LambdaQueryWrapper<PmHoursAlert>()
                .eq(PmHoursAlert::getHandledFlag, "0")
                .eq(PmHoursAlert::getEscalatedFlag, "0")
                .eq(PmHoursAlert::getDelFlag, "0"))) {
            if (PmMilestoneServiceImpl.workdaysBetween(alert.getAlertDate(), today) > ESCALATE_WORKDAYS) {
                PmProject p = projectDao.selectById(alert.getProjectId());
                noticeService.push("ROLE_PMO", null,
                        "工时偏差纠偏超期升级：" + (p == null ? alert.getProjectId() : p.getName()),
                        alert.getLevel() + " 预警（偏差 " + alert.getDeviationRate() + "%）已 "
                                + ESCALATE_WORKDAYS + " 个工作日未标记处理，升级 PMO 跟进。",
                        "PmHoursEscalate", alert.getId());
                insertRemind(alert);
                alert.setEscalatedFlag("1");
                alert.setEscalatedAt(LocalDateTime.now());
                alert.setUpdateBy("system");
                alert.setUpdateDate(LocalDateTime.now());
                alertDao.updateById(alert);
                escalated++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yellow", yellow);
        out.put("red", red);
        out.put("escalated", escalated);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 项目偏差率：(实际累计 - 计划累计) / 计划累计 × 100；计划 0 → null（跳过） */
    private BigDecimal deviationRateOf(String projectId) {
        BigDecimal plan = BigDecimal.ZERO;
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getDelFlag, "0"))) {
            if (w.getLevel() != null && w.getLevel() == PmWbs.LV_TASK && w.getEstHours() != null) {
                plan = plan.add(w.getEstHours());
            }
        }
        if (plan.signum() <= 0) {
            return null;
        }
        BigDecimal actual = BigDecimal.ZERO;
        for (PmHoursCollect c : collectDao.selectList(new LambdaQueryWrapper<PmHoursCollect>()
                .eq(PmHoursCollect::getProjectId, projectId)
                .eq(PmHoursCollect::getDelFlag, "0"))) {
            actual = actual.add(c.getHours());
        }
        return actual.subtract(plan).multiply(BigDecimal.valueOf(100))
                .divide(plan, 2, RoundingMode.HALF_UP);
    }

    /** 预警幂等：项目+级别已有记录则不重复生成；返回是否新建 */
    private boolean ensureAlert(PmProject p, String level, BigDecimal deviation, LocalDate today) {
        PmHoursAlert existing = alertDao.selectOne(new LambdaQueryWrapper<PmHoursAlert>()
                .eq(PmHoursAlert::getProjectId, p.getId())
                .eq(PmHoursAlert::getLevel, level)
                .eq(PmHoursAlert::getDelFlag, "0"));
        if (existing != null) {
            // 已有记录：更新偏差快照（若未处理），不重复通知
            if ("0".equals(existing.getHandledFlag())) {
                existing.setDeviationRate(deviation);
                existing.setUpdateDate(LocalDateTime.now());
                alertDao.updateById(existing);
            }
            return false;
        }
        PmHoursAlert alert = new PmHoursAlert();
        alert.setId(uuid());
        alert.setProjectId(p.getId());
        alert.setLevel(level);
        alert.setDeviationRate(deviation);
        alert.setAlertDate(today);
        alert.setHandledFlag("0");
        alert.setEscalatedFlag("0");
        alert.setCreateBy("system");
        alert.setCreateDate(LocalDateTime.now());
        alertDao.insert(alert);
        return true;
    }

    private void notifyAlert(PmProject p, String level, BigDecimal deviation, boolean alsoPmo) {
        String title = "工时偏差" + (PmHoursAlert.LV_RED.equals(level) ? "红色" : "黄色")
                + "预警：" + p.getName();
        String content = "项目工时偏差率 " + deviation + "%（"
                + (PmHoursAlert.LV_RED.equals(level) ? ">20 红色" : ">10 黄色")
                + "），请项目经理分析原因并在 5 个工作日内标记处理。";
        noticeService.push("ROLE_PM", null, title, content, "PmHoursAlert", p.getId() + ":" + level);
        if (alsoPmo) {
            noticeService.push("ROLE_PMO", null, title, content, "PmHoursAlertPmo", p.getId() + ":" + level);
        }
    }

    private void insertRemind(PmHoursAlert alert) {
        HrRemind r = new HrRemind();
        r.setId(uuid());
        r.setRemindType(HrRemind.TYPE_HOURS_ESCALATE);
        r.setEmpId(alert.getProjectId()); // UK(REMIND_TYPE,EMP_ID,DATE) 防重
        r.setDetail("工时偏差 " + alert.getLevel() + " 预警超 5 工作日未处理，升级 PMO");
        r.setRemindDate(LocalDate.now());
        r.setCreateBy("system");
        r.setCreateDate(LocalDateTime.now());
        remindDao.insert(r);
    }

    /** 项目偏差行（含四级汇总快照） */
    private Map<String, Object> projectVariance(PmProject p) {
        BigDecimal plan = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", p.getId());
        row.put("name", p.getName());
        row.put("projectNo", p.getProjectNo());
        row.put("status", p.getStatus());
        row.put("pmUserId", p.getPmUserId());
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, p.getId())
                .eq(PmWbs::getDelFlag, "0"))) {
            if (w.getLevel() != null && w.getLevel() == PmWbs.LV_TASK && w.getEstHours() != null) {
                plan = plan.add(w.getEstHours());
            }
        }
        for (PmHoursCollect c : collectDao.selectList(new LambdaQueryWrapper<PmHoursCollect>()
                .eq(PmHoursCollect::getProjectId, p.getId())
                .eq(PmHoursCollect::getDelFlag, "0"))) {
            actual = actual.add(c.getHours());
        }
        row.put("planHours", plan);
        row.put("actualHours", actual);
        if (plan.signum() > 0) {
            BigDecimal deviation = actual.subtract(plan).multiply(BigDecimal.valueOf(100))
                    .divide(plan, 2, RoundingMode.HALF_UP);
            row.put("deviationRate", deviation);
            BigDecimal yellowTh = BigDecimal.valueOf(sysParamService.getInt("HOURS_TOLERANCE_YELLOW", 10));
            BigDecimal redTh = BigDecimal.valueOf(sysParamService.getInt("HOURS_TOLERANCE_RED", 20));
            row.put("level", deviation.compareTo(redTh) > 0 ? PmHoursAlert.LV_RED
                    : deviation.compareTo(yellowTh) > 0 ? PmHoursAlert.LV_YELLOW : null);
        } else {
            row.put("deviationRate", null);
            row.put("level", null);
        }
        return row;
    }

    private List<Map<String, Object>> alertRows(String projectId) {
        LambdaQueryWrapper<PmHoursAlert> qw = new LambdaQueryWrapper<PmHoursAlert>()
                .eq(PmHoursAlert::getDelFlag, "0")
                .orderByDesc(PmHoursAlert::getAlertDate);
        if (hasText(projectId)) {
            qw.eq(PmHoursAlert::getProjectId, projectId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmHoursAlert a : alertDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("projectId", a.getProjectId());
            PmProject p = projectDao.selectById(a.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            row.put("level", a.getLevel());
            row.put("deviationRate", a.getDeviationRate());
            row.put("alertDate", a.getAlertDate() == null ? null : a.getAlertDate().toString());
            row.put("handledFlag", a.getHandledFlag());
            row.put("handledBy", a.getHandledBy());
            row.put("handledNote", a.getHandledNote());
            row.put("handledAt", a.getHandledAt() == null ? null : a.getHandledAt().toString());
            row.put("escalatedFlag", a.getEscalatedFlag());
            out.add(row);
        }
        return out;
    }

    private List<PmProject> projects(String projectId) {
        LambdaQueryWrapper<PmProject> qw = new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE);
        if (hasText(projectId)) {
            qw.eq(PmProject::getId, projectId);
        }
        return projectDao.selectList(qw);
    }

    private boolean hasAnyRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

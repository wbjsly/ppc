package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmHoursCollectDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmRateDao;
import com.erp.dao.pm.PmTimesheetLineDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmHoursCollect;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmRate;
import com.erp.entity.pm.PmTimesheet;
import com.erp.entity.pm.PmTimesheetLine;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmHoursCollectService;
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
 * 工时归集实现（add-timesheet-management；design D3 职级回落链：
 * 任务职级→项目覆盖→日期区间费率→当前费率兜底，快照保证调薪不污染历史）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmHoursCollectServiceImpl implements PmHoursCollectService {

    /** 无命中兜底默认费率（与 PmRateService.DEFAULT_RATE 同口径） */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("100");

    private final PmHoursCollectDao collectDao;
    private final PmTimesheetLineDao lineDao;
    private final PmWbsDao wbsDao;
    private final PmProjectDao projectDao;
    private final PmRateDao rateDao;
    private final NoticeService noticeService;
    private final com.erp.service.pm.PmCostCollectService costCollectService;

    @Override
    @Transactional
    public int collect(PmTimesheet sheet) {
        // 幂等：同工时单已归集则跳过
        Long existing = collectDao.selectCount(new LambdaQueryWrapper<PmHoursCollect>()
                .eq(PmHoursCollect::getTimesheetId, sheet.getId()));
        if (existing != null && existing > 0) {
            log.info("timesheet already collected, skip: {}", sheet.getId());
            return 0;
        }
        int written = 0;
        for (PmTimesheetLine line : lineDao.selectList(new LambdaQueryWrapper<PmTimesheetLine>()
                .eq(PmTimesheetLine::getTimesheetId, sheet.getId())
                .eq(PmTimesheetLine::getDelFlag, "0"))) {
            PmWbs task = wbsDao.selectById(line.getWbsId());
            PmProject project = projectDao.selectById(line.getProjectId());
            RateHit hit = resolveRate(task, project, sheet.getWorkDate(), sheet);
            PmHoursCollect c = new PmHoursCollect();
            c.setId(uuid());
            c.setTimesheetId(sheet.getId());
            c.setLineId(line.getId());
            c.setEmpId(sheet.getEmpId());
            c.setEmpName(sheet.getEmpName());
            c.setGrade(hit.grade());
            c.setGradeSource(hit.source());
            c.setProjectId(line.getProjectId());
            c.setWbsId(line.getWbsId());
            c.setWorkDate(sheet.getWorkDate());
            c.setHours(line.getHours());
            c.setRate(hit.rate());
            c.setLaborCost(line.getHours().multiply(hit.rate()).setScale(2, RoundingMode.HALF_UP));
            c.setCollectDate(LocalDate.now());
            c.setCreateBy("system");
            c.setCreateDate(LocalDateTime.now());
            collectDao.insert(c);
            // 人工成本腿同步写五类统一归集（add-cost-collection-freeze，LABOR 来源=归集行 ID；
            // 失败 fail fast——同事务回滚，与培训费用凭证先例一致）
            costCollectService.collect(com.erp.service.pm.PmCostCollectService.TYPE_LABOR,
                    com.erp.service.pm.PmCostCollectService.SRC_HOURS_COLLECT, c.getId(),
                    c.getProjectId(), c.getWbsId(), c.getLaborCost(), LocalDate.now());
            written++;
        }
        log.info("timesheet collected: {} rows={}", sheet.getId(), written);
        return written;
    }

    /**
     * 职级费率回落链（D3）：
     * 1. 任务 ownerGrade + 项目 rateOverride 命中 → PROJECT（与预算 rateFor 同优先级）
     * 2. 任务职级 + 日期区间费率命中 → TASK
     * 3. 无区间命中 → L4 提示 + 当前在职费率兜底 → CURRENT
     * 4. 无任务职级 → 项目覆盖 → 当前费率 → 默认 100（L4 提示补维护）
     */
    private RateHit resolveRate(PmWbs task, PmProject project, LocalDate workDate, PmTimesheet sheet) {
        String grade = task == null ? null : task.getOwnerGrade();
        // 项目覆盖优先
        if (hasText(grade) && project != null && hasText(project.getRateOverride())) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(project.getRateOverride());
                if (node.has(grade) && !node.get(grade).isNull()) {
                    return new RateHit(grade, PmHoursCollect.SRC_PROJECT,
                            new BigDecimal(node.get(grade).asText()));
                }
            } catch (Exception e) {
                log.warn("rate override parse failed: {}", e.getMessage());
            }
        }
        if (hasText(grade)) {
            // 日期区间匹配（期间型费率，170 加列）
            PmRate ranged = rateDao.selectOne(new LambdaQueryWrapper<PmRate>()
                    .eq(PmRate::getGrade, grade)
                    .eq(PmRate::getStatus, "1")
                    .eq(PmRate::getDelFlag, "0")
                    .le(PmRate::getEffFrom, workDate)
                    .and(w -> w.isNull(PmRate::getEffTo).or().ge(PmRate::getEffTo, workDate))
                    .orderByDesc(PmRate::getEffFrom)
                    .last("LIMIT 1"));
            if (ranged != null && ranged.getRatePerHour() != null) {
                return new RateHit(grade, PmHoursCollect.SRC_TASK, ranged.getRatePerHour());
            }
            // 无区间命中 → L4 提示 + 当前在职费率兜底
            pushRateGapNotice(grade, workDate, sheet);
            PmRate current = rateDao.selectOne(new LambdaQueryWrapper<PmRate>()
                    .eq(PmRate::getGrade, grade)
                    .eq(PmRate::getStatus, "1")
                    .eq(PmRate::getDelFlag, "0")
                    .orderByDesc(PmRate::getEffFrom)
                    .last("LIMIT 1"));
            BigDecimal rate = current != null && current.getRatePerHour() != null
                    ? current.getRatePerHour() : DEFAULT_RATE;
            return new RateHit(grade, PmHoursCollect.SRC_CURRENT, rate);
        }
        // 无任务职级：L4 提示 + 默认费率
        noticeService.push("ROLE_HR", null,
                "工时归集职级缺失",
                "员工 " + sheet.getEmpName() + " 的任务未维护职级，暂按默认费率计算，请补充 WBS 任务负责人职级",
                "PmRateGap", sheet.getId());
        return new RateHit(null, PmHoursCollect.SRC_CURRENT, DEFAULT_RATE);
    }

    private void pushRateGapNotice(String grade, LocalDate workDate, PmTimesheet sheet) {
        noticeService.push("ROLE_HR", null,
                "工时费率无生效区间命中",
                "职级 " + grade + " 在 " + workDate + " 无生效费率区间，暂按当前在职费率归集（员工 "
                        + sheet.getEmpName() + "），请 HR 补充配置",
                "PmRateGap", sheet.getId() + ":" + grade);
    }

    @Override
    public Map<String, Object> summary(String projectId) {
        // 归集实际
        Map<String, BigDecimal> actualByWbs = new LinkedHashMap<>();
        Map<String, BigDecimal> costByWbs = new LinkedHashMap<>();
        LambdaQueryWrapper<PmHoursCollect> qw = new LambdaQueryWrapper<PmHoursCollect>()
                .eq(PmHoursCollect::getDelFlag, "0");
        if (hasText(projectId)) {
            qw.eq(PmHoursCollect::getProjectId, projectId);
        }
        for (PmHoursCollect c : collectDao.selectList(qw)) {
            actualByWbs.merge(c.getWbsId(), c.getHours(), BigDecimal::add);
            costByWbs.merge(c.getWbsId(), c.getLaborCost(), BigDecimal::add);
        }
        // WBS 树 + 预估
        Map<String, PmWbs> nodes = new LinkedHashMap<>();
        LambdaQueryWrapper<PmWbs> wq = new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getDelFlag, "0");
        if (hasText(projectId)) {
            wq.eq(PmWbs::getProjectId, projectId);
        }
        for (PmWbs w : wbsDao.selectList(wq)) {
            nodes.put(w.getId(), w);
        }
        // 任务行
        List<Map<String, Object>> taskRows = new ArrayList<>();
        Map<String, BigDecimal> pkgActual = new LinkedHashMap<>();
        Map<String, BigDecimal> pkgPlan = new LinkedHashMap<>();
        for (PmWbs w : nodes.values()) {
            if (w.getLevel() == null || w.getLevel() != PmWbs.LV_TASK) {
                continue;
            }
            BigDecimal plan = w.getEstHours() == null ? BigDecimal.ZERO : w.getEstHours();
            BigDecimal actual = actualByWbs.getOrDefault(w.getId(), BigDecimal.ZERO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wbsId", w.getId());
            row.put("wbsCode", w.getWbsCode());
            row.put("name", w.getName());
            row.put("parentId", w.getParentId());
            row.put("planHours", plan);
            row.put("actualHours", actual);
            row.put("laborCost", costByWbs.getOrDefault(w.getId(), BigDecimal.ZERO));
            taskRows.add(row);
            if (hasText(w.getParentId())) {
                pkgActual.merge(w.getParentId(), actual, BigDecimal::add);
                pkgPlan.merge(w.getParentId(), plan, BigDecimal::add);
            }
        }
        // 工作包与阶段汇总
        Map<String, BigDecimal> phaseActual = new LinkedHashMap<>();
        Map<String, BigDecimal> phasePlan = new LinkedHashMap<>();
        List<Map<String, Object>> pkgRows = new ArrayList<>();
        for (PmWbs w : nodes.values()) {
            if (w.getLevel() == null || w.getLevel() != PmWbs.LV_PACKAGE) {
                continue;
            }
            BigDecimal actual = pkgActual.getOrDefault(w.getId(), BigDecimal.ZERO);
            BigDecimal plan = pkgPlan.getOrDefault(w.getId(), BigDecimal.ZERO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wbsId", w.getId());
            row.put("name", w.getName());
            row.put("parentId", w.getParentId());
            row.put("planHours", plan);
            row.put("actualHours", actual);
            pkgRows.add(row);
            if (hasText(w.getParentId())) {
                phaseActual.merge(w.getParentId(), actual, BigDecimal::add);
                phasePlan.merge(w.getParentId(), plan, BigDecimal::add);
            }
        }
        List<Map<String, Object>> phaseRows = new ArrayList<>();
        BigDecimal totalPlan = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (PmWbs w : nodes.values()) {
            if (w.getLevel() == null || w.getLevel() != PmWbs.LV_PHASE) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wbsId", w.getId());
            row.put("name", w.getName());
            row.put("planHours", phasePlan.getOrDefault(w.getId(), BigDecimal.ZERO));
            row.put("actualHours", phaseActual.getOrDefault(w.getId(), BigDecimal.ZERO));
            phaseRows.add(row);
            totalPlan = totalPlan.add(phasePlan.getOrDefault(w.getId(), BigDecimal.ZERO));
            totalActual = totalActual.add(phaseActual.getOrDefault(w.getId(), BigDecimal.ZERO));
        }
        for (BigDecimal cost : costByWbs.values()) {
            totalCost = totalCost.add(cost);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("planHours", totalPlan);
        out.put("actualHours", totalActual);
        out.put("laborCost", totalCost);
        out.put("phases", phaseRows);
        out.put("packages", pkgRows);
        out.put("tasks", taskRows);
        return out;
    }

    /** 费率命中（rate + 职级 + 来源） */
    private record RateHit(String grade, String source, BigDecimal rate) {
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmWbs;
import com.erp.service.SysParamService;
import com.erp.service.pm.PmTrialCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试制成本汇总实现（add-rnd-material，design D5）。
 * 一份归集数据两个视角：项目整体（11.6）/ 试制子集（11.9.2），口径不漂移。
 * 材料+工时+外协 = TRIAL 任务上的 MATERIAL+LABOR+SUBCONTRACT 归集合计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTrialCostServiceImpl implements PmTrialCostService {

    private final PmWbsDao wbsDao;
    private final PmCostCollectDao costCollectDao;
    private final PmBudgetLineDao budgetLineDao;
    private final SysParamService sysParamService;

    @Override
    public Map<String, Object> summary(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new ServiceException(422, "项目必填");
        }
        // TRIAL 任务集
        List<PmWbs> trialTasks = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getTaskType, PmWbs.TYPE_TRIAL)
                .eq(PmWbs::getDelFlag, "0"));
        Set<String> trialIds = trialTasks.stream().map(PmWbs::getId).collect(Collectors.toSet());

        // 归集聚合：材料+工时+外协
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal subcontract = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        if (!trialIds.isEmpty()) {
            for (PmCostCollect c : costCollectDao.selectList(new LambdaQueryWrapper<PmCostCollect>()
                    .in(PmCostCollect::getWbsId, trialIds)
                    .eq(PmCostCollect::getDelFlag, "0"))) {
                BigDecimal amt = c.getAmount() == null ? BigDecimal.ZERO : c.getAmount();
                total = total.add(amt);
                byType.merge(c.getCostType(), amt, BigDecimal::add);
                switch (c.getCostType() == null ? "" : c.getCostType()) {
                    case "MATERIAL" -> material = material.add(amt);
                    case "LABOR" -> labor = labor.add(amt);
                    case "SUBCONTRACT" -> subcontract = subcontract.add(amt);
                    default -> { }
                }
            }
        }
        // 试制任务预算（六列合计）
        BigDecimal budget = BigDecimal.ZERO;
        for (PmBudgetLine b : budgetLineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .in(PmBudgetLine::getWbsId, trialIds.isEmpty() ? List.of("") : trialIds)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            budget = budget.add(nz(b.getLabor())).add(nz(b.getMaterial()))
                    .add(nz(b.getSubcontract())).add(nz(b.getTravel()))
                    .add(nz(b.getOther())).add(nz(b.getIndirect()));
        }
        // 偏差率（预算>0 时）
        BigDecimal deviationRate = null;
        if (budget.signum() > 0) {
            deviationRate = total.subtract(budget)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(budget, 2, RoundingMode.HALF_UP);
        }
        BigDecimal tolerance = costTolerance();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("trialTaskCount", trialTasks.size());
        out.put("material", material);
        out.put("labor", labor);
        out.put("subcontract", subcontract);
        out.put("totalActual", total);
        out.put("budget", budget);
        out.put("deviationRate", deviationRate);
        out.put("costTolerance", tolerance);
        out.put("byType", byType);
        return out;
    }

    private BigDecimal costTolerance() {
        String v = null;
        try {
            v = sysParamService.getValue("COST_TOLERANCE");
        } catch (Exception e) {
            log.warn("read COST_TOLERANCE failed: {}", e.getMessage());
        }
        if (v == null || v.isBlank()) {
            return new BigDecimal("0.5");
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return new BigDecimal("0.5");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmOverBudgetDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmOverBudget;
import com.erp.entity.pm.PmProject;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmOverBudgetService;
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
 * 超预算分级审批解冻实现（add-cost-collection-freeze，design D4）。
 * BIZ_TYPE=PmOverBudget；BR-4.8-22 动态路由：≤5% ROLE_PMO / ≤10% ROLE_FINANCE / >10% ROLE_EXEC。
 * 通过回调解冻 + EXTRA_BUDGET>0 时复用基线快照服务更新预算行 + budget_change 留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmOverBudgetServiceImpl implements PmOverBudgetService {

    /** 审批 BIZ_TYPE（PmOverBudgetApprovalCallback 对齐） */
    public static final String BIZ_OVER_BUDGET = "PmOverBudget";

    private final PmOverBudgetDao overBudgetDao;
    private final PmProjectDao projectDao;
    private final PmBudgetLineDao budgetLineDao;
    private final PmCostCollectDao costCollectDao;
    private final ApprovalEngine approvalEngine;

    @Override
    @Transactional
    public Map<String, Object> submit(Map<String, Object> payload) {
        String projectId = str(payload.get("projectId"));
        PmProject p = hasText(projectId) ? projectDao.selectById(projectId) : null;
        if (p == null) {
            throw new ServiceException(422, "项目不存在");
        }
        if (!PmProject.FREEZE_YES.equals(p.getFreezeFlag())) {
            throw new ServiceException(422, "项目未冻结，无需超预算申请");
        }
        String reason = str(payload.get("reason"));
        if (!hasText(reason)) {
            throw new ServiceException(422, "超支原因必填");
        }
        // 在途防重
        Long pending = overBudgetDao.selectCount(new LambdaQueryWrapper<PmOverBudget>()
                .eq(PmOverBudget::getProjectId, projectId)
                .eq(PmOverBudget::getStatus, PmOverBudget.ST_PENDING)
                .eq(PmOverBudget::getDelFlag, "0"));
        if (pending != null && pending > 0) {
            throw new ServiceException(409, "该项目已有在途超预算申请，请勿重复提交");
        }
        BigDecimal baseline = baselineOf(projectId);
        BigDecimal actual = actualOf(projectId);
        if (baseline.signum() <= 0) {
            throw new ServiceException(422, "项目预算基线缺失，无法计算超支");
        }
        BigDecimal overAmount = actual.subtract(baseline);
        if (overAmount.signum() <= 0) {
            throw new ServiceException(422, "实际累计未超基线（实际 " + actual + " / 基线 "
                    + baseline + "），无需超预算申请");
        }
        BigDecimal overRate = overAmount.multiply(new BigDecimal("100"))
                .divide(baseline, 2, RoundingMode.HALF_UP);
        // BR-4.8-22 动态路由
        String role;
        if (overRate.compareTo(new BigDecimal("10")) > 0) {
            role = PmOverBudget.ROUTE_EXEC;
        } else if (overRate.compareTo(new BigDecimal("5")) > 0) {
            role = PmOverBudget.ROUTE_FINANCE;
        } else {
            role = PmOverBudget.ROUTE_PMO;
        }
        BigDecimal extra = dec(payload.get("extraBudget"));
        if (extra == null) {
            extra = BigDecimal.ZERO; // 缺省=纯解冻
        }
        if (extra.signum() < 0) {
            throw new ServiceException(422, "追加预算需求须为非负数（0=纯解冻）");
        }
        PmOverBudget ob = new PmOverBudget();
        ob.setId(uuid());
        ob.setApplyNo(nextNo());
        ob.setProjectId(projectId);
        ob.setOverAmount(overAmount);
        ob.setOverRate(overRate);
        ob.setReason(reason);
        ob.setSavingMeasure(str(payload.get("savingMeasure")));
        ob.setExtraBudget(extra);
        ob.setApprovalRole(role);
        ob.setStatus(PmOverBudget.ST_PENDING);
        ob.setCreateBy(currentUser());
        ob.setCreateDate(LocalDateTime.now());
        overBudgetDao.insert(ob);
        String roleName = PmOverBudget.ROUTE_EXEC.equals(role) ? "分管领导"
                : PmOverBudget.ROUTE_FINANCE.equals(role) ? "财务" : "PMO";
        String title = "超预算审批：" + p.getName() + "（超支 " + overAmount + " 元 / " + overRate
                + "%，" + (extra.signum() > 0 ? "含追加 " + extra + " 元" : "纯解冻") + "）";
        var inst = approvalEngine.submit(
                BIZ_OVER_BUDGET,
                ob.getId(),
                title,
                null,
                List.of(List.of(ApprovalNodeSpec.sign(role, roleName + "超预算审批"))));
        ob.setApprId(inst.getId());
        ob.setUpdateDate(LocalDateTime.now());
        overBudgetDao.updateById(ob);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ob.getId());
        out.put("applyNo", ob.getApplyNo());
        out.put("overAmount", overAmount);
        out.put("overRate", overRate);
        out.put("approvalRole", role);
        out.put("apprId", inst.getId());
        return out;
    }

    @Override
    public List<Map<String, Object>> listFrozen() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmProject p : projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE)
                .eq(PmProject::getFreezeFlag, PmProject.FREEZE_YES)
                .orderByDesc(PmProject::getFrozenAt))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", p);
            BigDecimal baseline = baselineOf(p.getId());
            BigDecimal actual = actualOf(p.getId());
            row.put("baseline", baseline);
            row.put("actual", actual);
            row.put("overAmount", actual.subtract(baseline));
            row.put("overRate", baseline.signum() <= 0 ? null
                    : actual.subtract(baseline).multiply(new BigDecimal("100"))
                            .divide(baseline, 2, RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String projectId, String status) {
        LambdaQueryWrapper<PmOverBudget> qw = new LambdaQueryWrapper<PmOverBudget>()
                .eq(PmOverBudget::getDelFlag, "0")
                .orderByDesc(PmOverBudget::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmOverBudget::getProjectId, projectId);
        }
        if (hasText(status)) {
            qw.eq(PmOverBudget::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmOverBudget ob : overBudgetDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("apply", ob);
            PmProject p = projectDao.selectById(ob.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmOverBudget ob = overBudgetDao.selectById(id);
        if (ob == null || "1".equals(ob.getDelFlag())) {
            throw new ServiceException(422, "超预算申请不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", ob);
        PmProject p = projectDao.selectById(ob.getProjectId());
        out.put("project", p);
        return out;
    }

    // ---- helpers ----

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

    private String nextNo() {
        String prefix = "OBB" + LocalDate.now().toString().replace("-", "");
        Long n = overBudgetDao.selectCount(new LambdaQueryWrapper<PmOverBudget>()
                .likeRight(PmOverBudget::getApplyNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(o));
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

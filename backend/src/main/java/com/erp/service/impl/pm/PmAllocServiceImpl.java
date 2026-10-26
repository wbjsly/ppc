package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmAllocDetailDao;
import com.erp.dao.pm.PmAllocRuleDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmCostPoolDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmAllocDetail;
import com.erp.entity.pm.PmAllocRule;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmCostPool;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmAllocService;
import com.erp.service.pm.PmCostCollectService;
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
 * 间接成本分摊引擎实现（add-cost-collection-freeze，FR-4.8-5-5）。
 * HOURS=项目当期 LABOR 归集成本占比；BUDGET=项目预算基线占比。
 * 缺规则项目跳过 + L4 提示（其份额按已配置项目重算占比）；同 PERIOD+POOL 幂等防重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmAllocServiceImpl implements PmAllocService {

    private final PmAllocRuleDao ruleDao;
    private final PmCostPoolDao poolDao;
    private final PmAllocDetailDao detailDao;
    private final PmProjectDao projectDao;
    private final PmBudgetLineDao budgetLineDao;
    private final PmCostCollectDao costCollectDao;
    private final PmCostCollectService costCollectService;
    private final NoticeService noticeService;
    private final com.erp.dao.system.SysNoticeDao noticeDao;

    // ---- 规则 ----

    @Override
    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmAllocRule r : ruleDao.selectList(new LambdaQueryWrapper<PmAllocRule>()
                .eq(PmAllocRule::getDelFlag, "0")
                .orderByAsc(PmAllocRule::getProjectId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule", r);
            PmProject p = projectDao.selectById(r.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> saveRule(Map<String, Object> payload) {
        String projectId = str(payload.get("projectId"));
        String type = str(payload.get("ruleType"));
        if (!hasText(projectId)) {
            throw new ServiceException(422, "项目必填");
        }
        if (!RULE_HOURS.equals(type) && !RULE_BUDGET.equals(type)) {
            throw new ServiceException(422, "规则类型须为 HOURS（工时占比）或 BUDGET（预算占比）");
        }
        // 每项目至多一条生效规则：同项目既有生效规则 → 覆盖类型
        PmAllocRule exist = ruleDao.selectOne(new LambdaQueryWrapper<PmAllocRule>()
                .eq(PmAllocRule::getProjectId, projectId)
                .eq(PmAllocRule::getStatus, "1")
                .eq(PmAllocRule::getDelFlag, "0")
                .last("LIMIT 1"));
        PmAllocRule r = exist != null ? exist : new PmAllocRule();
        boolean isNew = exist == null;
        if (isNew) {
            r.setId(uuid());
            r.setStatus("1");
            r.setCreateBy(currentUser());
            r.setCreateDate(LocalDateTime.now());
        }
        r.setProjectId(projectId);
        r.setRuleType(type);
        if (isNew) {
            ruleDao.insert(r);
        } else {
            r.setUpdateBy(currentUser());
            r.setUpdateDate(LocalDateTime.now());
            ruleDao.updateById(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        return out;
    }

    @Override
    @Transactional
    public void disableRule(String id) {
        PmAllocRule r = ruleDao.selectById(id);
        if (r == null || "1".equals(r.getDelFlag())) {
            throw new ServiceException(422, "规则不存在");
        }
        r.setStatus("0");
        r.setUpdateBy(currentUser());
        r.setUpdateDate(LocalDateTime.now());
        ruleDao.updateById(r);
    }

    // ---- 费用池 ----

    @Override
    public List<Map<String, Object>> listPools(String period) {
        LambdaQueryWrapper<PmCostPool> qw = new LambdaQueryWrapper<PmCostPool>()
                .eq(PmCostPool::getDelFlag, "0")
                .orderByDesc(PmCostPool::getCreateDate);
        if (hasText(period)) {
            qw.eq(PmCostPool::getPeriod, period);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmCostPool p : poolDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pool", p);
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> savePool(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        if (!hasText(period) || period.length() != 7) {
            throw new ServiceException(422, "期间格式须为 yyyy-MM");
        }
        String category = str(payload.get("expenseCategory"));
        if (!hasText(category)) {
            throw new ServiceException(422, "费用类别必填");
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "费用金额须为正");
        }
        PmCostPool p = new PmCostPool();
        p.setId(uuid());
        p.setPeriod(period);
        p.setExpenseCategory(category);
        p.setAmount(amount);
        p.setRemark(str(payload.get("remark")));
        p.setCreateBy(currentUser());
        p.setCreateDate(LocalDateTime.now());
        poolDao.insert(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        return out;
    }

    @Override
    public List<Map<String, Object>> listDetails(String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmAllocDetail d : detailDao.selectList(new LambdaQueryWrapper<PmAllocDetail>()
                .eq(PmAllocDetail::getDelFlag, "0")
                .eq(PmAllocDetail::getPeriod, period)
                .orderByAsc(PmAllocDetail::getPoolId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("detail", d);
            PmProject p = projectDao.selectById(d.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> runPeriod(String period) {
        if (!hasText(period) || period.length() != 7) {
            throw new ServiceException(422, "期间格式须为 yyyy-MM");
        }
        List<PmCostPool> pools = poolDao.selectList(new LambdaQueryWrapper<PmCostPool>()
                .eq(PmCostPool::getPeriod, period)
                .eq(PmCostPool::getDelFlag, "0"));
        int allocated = 0;
        int skippedPools = 0;
        for (PmCostPool pool : pools) {
            // 幂等：同 PERIOD + POOL_ID 已有明细 → 跳过（防重复凭证）
            Long done = detailDao.selectCount(new LambdaQueryWrapper<PmAllocDetail>()
                    .eq(PmAllocDetail::getPeriod, period)
                    .eq(PmAllocDetail::getPoolId, pool.getId())
                    .eq(PmAllocDetail::getDelFlag, "0"));
            if (done != null && done > 0) {
                skippedPools++;
                continue;
            }
            allocated += allocateOne(pool, period);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("poolCount", pools.size());
        out.put("allocatedDetails", allocated);
        out.put("skippedPools", skippedPools);
        return out;
    }

    /** 单费用池分摊：按各项目规则基数占比拆分 */
    private int allocateOne(PmCostPool pool, String period) {
        // 生效规则 + 在项项目
        List<PmAllocRule> rules = ruleDao.selectList(new LambdaQueryWrapper<PmAllocRule>()
                .eq(PmAllocRule::getStatus, "1")
                .eq(PmAllocRule::getDelFlag, "0"));
        Map<String, String> ruleByProject = new LinkedHashMap<>();
        for (PmAllocRule r : rules) {
            PmProject p = projectDao.selectById(r.getProjectId());
            if (p != null && PmProject.ST_ACTIVE.equals(p.getStatus())) {
                ruleByProject.put(r.getProjectId(), r.getRuleType());
            }
        }
        if (ruleByProject.isEmpty()) {
            noticeService.push("ROLE_PMO", null,
                    "间接分摊无生效规则：" + period,
                    "期间 " + period + " 费用池（" + pool.getExpenseCategory() + " "
                            + pool.getAmount() + " 元）无任何项目配置分摊规则，本期暂不归集，请维护规则",
                    "PmAllocNoRule", pool.getId());
            return 0;
        }
        // 缺规则的在项项目 → L4 提示（其份额不参与摊派，按其余项目重算占比；FR-4.8-5-5 异常列）
        for (PmProject p : projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE))) {
            if (!ruleByProject.containsKey(p.getId())) {
                String bizId = p.getId() + ":" + period;
                Long existing = noticeDao.selectCount(new LambdaQueryWrapper<com.erp.entity.system.SysNotice>()
                        .eq(com.erp.entity.system.SysNotice::getBizType, "PmAllocMissingRule")
                        .eq(com.erp.entity.system.SysNotice::getBizId, bizId));
                if (existing == null || existing == 0) {
                    noticeService.push("ROLE_PMO", null,
                            "项目缺间接分摊规则：" + p.getName(),
                            "在项项目「" + p.getName() + "」未配置间接成本分摊规则（期间 " + period
                                    + "），本期暂不分摊其间接成本，请在 11.6.1 间接分摊 Tab 维护规则",
                            "PmAllocMissingRule", bizId);
                }
            }
        }
        // 基数计算
        Map<String, BigDecimal> bases = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ruleByProject.entrySet()) {
            BigDecimal base = PmAllocService.RULE_HOURS.equals(e.getValue())
                    ? laborOfPeriod(e.getKey(), period)
                    : baselineOf(e.getKey());
            if (base.signum() <= 0) {
                // 基数为 0 的项目不参与摊派（其份额按其余项目重算）
                continue;
            }
            bases.put(e.getKey(), base);
        }
        if (bases.isEmpty()) {
            noticeService.push("ROLE_PMO", null,
                    "间接分摊基数为零：" + period,
                    "期间 " + period + " 已配置规则项目的分摊基数（工时成本/预算基线）均为零，本期暂不归集",
                    "PmAllocZeroBase", pool.getId());
            return 0;
        }
        BigDecimal totalBase = bases.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // 逐项目分摊（最大余额法消化尾差）
        BigDecimal allocatedSum = BigDecimal.ZERO;
        int n = 0;
        List<String> keys = new ArrayList<>(bases.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String projectId = keys.get(i);
            BigDecimal base = bases.get(projectId);
            BigDecimal amount;
            if (i == keys.size() - 1) {
                amount = pool.getAmount().subtract(allocatedSum); // 尾差给最后一家
            } else {
                amount = pool.getAmount().multiply(base)
                        .divide(totalBase, 2, RoundingMode.HALF_UP);
                allocatedSum = allocatedSum.add(amount);
            }
            if (amount.signum() <= 0) {
                continue;
            }
            BigDecimal rate = base.multiply(new BigDecimal("100"))
                    .divide(totalBase, 4, RoundingMode.HALF_UP);
            String collectId = costCollectService.collect(PmCostCollectService.TYPE_INDIRECT,
                    PmCostCollectService.SRC_ALLOC, pool.getId() + ":" + projectId,
                    projectId, null, amount, LocalDate.now());
            PmAllocDetail d = new PmAllocDetail();
            d.setId(uuid());
            d.setPeriod(period);
            d.setPoolId(pool.getId());
            d.setProjectId(projectId);
            d.setAllocBase(base);
            d.setAllocRate(rate);
            d.setAmount(amount);
            d.setCollectId(collectId);
            d.setCreateBy("system");
            d.setCreateDate(LocalDateTime.now());
            detailDao.insert(d);
            n++;
        }
        return n;
    }

    /** 项目当期（period 月）LABOR 归集成本合计 */
    private BigDecimal laborOfPeriod(String projectId, String period) {
        BigDecimal sum = BigDecimal.ZERO;
        java.time.YearMonth ym = java.time.YearMonth.parse(period);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        for (PmCostCollect c : costCollectDao.selectList(new LambdaQueryWrapper<PmCostCollect>()
                .eq(PmCostCollect::getProjectId, projectId)
                .eq(PmCostCollect::getCostType, PmCostCollectService.TYPE_LABOR)
                .eq(PmCostCollect::getDelFlag, "0")
                .ge(PmCostCollect::getCollectDate, from)
                .le(PmCostCollect::getCollectDate, to))) {
            sum = sum.add(c.getAmount());
        }
        return sum;
    }

    /** 项目预算基线合计（六列之和） */
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

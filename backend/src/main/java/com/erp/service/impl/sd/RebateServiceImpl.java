package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArWriteoffDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.sd.RebateBudgetDao;
import com.erp.dao.sd.RebatePolicyDao;
import com.erp.dao.sd.RebateSettlementDao;
import com.erp.dao.sd.RebateTargetDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArWriteoff;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.sd.RebateBudget;
import com.erp.entity.sd.RebatePolicy;
import com.erp.entity.sd.RebateSettlement;
import com.erp.entity.sd.RebateTarget;
import com.erp.entity.sd.SdReturn;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.sd.RebateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 返利结算实现（tasks 11.2~11.8，spec sales-rebate）。
 */
@Slf4j
@Service
public class RebateServiceImpl implements RebateService {

    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})Q([1-4])$");
    /** 退货终态（不构成「数据不完整」）：完成/取消/驳回 */
    private static final List<String> RETURN_TERMINAL =
            List.of(SdReturn.ST_DONE, SdReturn.ST_CANCELLED, SdReturn.ST_REJECTED);

    private final RebateTargetDao targetDao;
    private final RebatePolicyDao policyDao;
    private final RebateBudgetDao budgetDao;
    private final RebateSettlementDao settleDao;
    private final ArInvoiceDao arDao;
    private final ArWriteoffDao writeoffDao;
    private final SdReturnDao returnDao;
    private final MdmCustomerGroupDao customerDao;
    private final ApprovalEngine approvalEngine;
    private final GlVoucherService voucherService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RebateServiceImpl(RebateTargetDao targetDao,
                             RebatePolicyDao policyDao,
                             RebateBudgetDao budgetDao,
                             RebateSettlementDao settleDao,
                             ArInvoiceDao arDao,
                             ArWriteoffDao writeoffDao,
                             SdReturnDao returnDao,
                             MdmCustomerGroupDao customerDao,
                             ApprovalEngine approvalEngine,
                             GlVoucherService voucherService) {
        this.targetDao = targetDao;
        this.policyDao = policyDao;
        this.budgetDao = budgetDao;
        this.settleDao = settleDao;
        this.arDao = arDao;
        this.writeoffDao = writeoffDao;
        this.returnDao = returnDao;
        this.customerDao = customerDao;
        this.approvalEngine = approvalEngine;
        this.voucherService = voucherService;
    }

    private static final String[] ROLES = {
            "ROLE_SALES_MGR", "ROLE_SALES_DIRECTOR", "ROLE_FINANCE_MGR", "ROLE_ADMIN"};

    // ==================== 三配置（11.2） ====================

    @Override
    public Page<RebateTarget> targetPage(long current, long size,
                                         String customerId, String quarter) {
        requireAny("查询季度目标");
        LambdaQueryWrapper<RebateTarget> qw = new LambdaQueryWrapper<RebateTarget>()
                .eq(isNotBlank(customerId), RebateTarget::getCustomerId, customerId)
                .eq(isNotBlank(quarter), RebateTarget::getQuarter, quarter)
                .orderByDesc(RebateTarget::getQuarter);
        return targetDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public RebateTarget saveTarget(RebateTarget target) {
        requireAny("维护季度目标");
        if (isBlank(target.getCustomerId())) {
            throw new ServiceException(422, "客户必填");
        }
        if (isBlank(target.getQuarter()) || !QUARTER.matcher(target.getQuarter()).matches()) {
            throw new ServiceException(422, "季度格式应为 yyyyQn，如 2026Q4");
        }
        if (target.getTargetAmt() == null || target.getTargetAmt().signum() <= 0) {
            throw new ServiceException(422, "季度目标金额必须大于 0");
        }
        // 同客户同季度唯一（存在则更新）
        RebateTarget exist = targetDao.selectOne(new LambdaQueryWrapper<RebateTarget>()
                .eq(RebateTarget::getCustomerId, target.getCustomerId())
                .eq(RebateTarget::getQuarter, target.getQuarter()));
        MdmCustomerGroup cust = customerDao.selectById(target.getCustomerId());
        if (cust == null) {
            throw new ServiceException(404, "客户不存在：" + target.getCustomerId());
        }
        target.setCustomerCode(cust.getCustomerCode());
        target.setCustomerName(cust.getCustomerName());
        if (exist != null) {
            target.setId(exist.getId());
            target.setVerNo(exist.getVerNo());
            targetDao.updateById(target);
            return target;
        }
        target.setId(uuid());
        target.setCreateBy(currentUser());
        targetDao.insert(target);
        return target;
    }

    @Override
    @Transactional
    public void removeTarget(String id) {
        requireAny("删除季度目标");
        RebateTarget t = targetDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "目标不存在：" + id);
        }
        targetDao.deleteById(id);
    }

    @Override
    public List<RebatePolicy> policyList(String customerId) {
        requireAny("查询返利政策");
        return policyDao.selectList(new LambdaQueryWrapper<RebatePolicy>()
                .eq(isNotBlank(customerId), RebatePolicy::getCustomerId, customerId)
                .orderByAsc(RebatePolicy::getBandFrom));
    }

    @Override
    @Transactional
    public RebatePolicy savePolicy(RebatePolicy policy) {
        requireAny("维护返利政策");
        if (policy.getBandFrom() == null || policy.getRebateRate() == null) {
            throw new ServiceException(422, "达成率下限与返利率必填");
        }
        if (policy.getBandTo() != null && policy.getBandTo().compareTo(policy.getBandFrom()) <= 0) {
            throw new ServiceException(422, "达成率上限必须大于下限");
        }
        if (isBlank(policy.getPolicyNo())) {
            policy.setPolicyNo("POL-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + policy.getBandFrom().stripTrailingZeros().toPlainString());
        }
        if (isBlank(policy.getStatus())) {
            policy.setStatus(RebatePolicy.ST_EFFECTIVE);
        }
        if (policy.getId() == null || policyDao.selectById(policy.getId()) == null) {
            policy.setId(uuid());
            policy.setCreateBy(currentUser());
            policyDao.insert(policy);
        } else {
            policyDao.updateById(policy);
        }
        return policy;
    }

    @Override
    @Transactional
    public void removePolicy(String id) {
        requireAny("删除返利政策");
        if (policyDao.selectById(id) == null) {
            throw new ServiceException(404, "政策阶梯不存在：" + id);
        }
        policyDao.deleteById(id);
    }

    @Override
    public RebateBudget getBudget(int year) {
        requireAny("查询返利预算");
        return budgetDao.selectOne(new LambdaQueryWrapper<RebateBudget>()
                .eq(RebateBudget::getBudgetYear, year));
    }

    @Override
    @Transactional
    public RebateBudget saveBudget(RebateBudget budget) {
        requireAny("维护返利预算");
        if (budget.getBudgetYear() == null) {
            throw new ServiceException(422, "预算年度必填");
        }
        if (budget.getTotalAmt() == null || budget.getTotalAmt().signum() <= 0) {
            throw new ServiceException(422, "年度预算总额必须大于 0");
        }
        // 仅总额 → 按季均分（FR-4.3-8-3：季度预算由年度预算按季度分解）
        if (budget.getQ1Amt() == null) {
            BigDecimal q = budget.getTotalAmt().divide(new BigDecimal("4"), 2, RoundingMode.HALF_UP);
            budget.setQ1Amt(q);
            budget.setQ2Amt(q);
            budget.setQ3Amt(q);
            budget.setQ4Amt(budget.getTotalAmt().subtract(q).subtract(q).subtract(q));
        } else {
            BigDecimal sum = nvl(budget.getQ1Amt()).add(nvl(budget.getQ2Amt()))
                    .add(nvl(budget.getQ3Amt())).add(nvl(budget.getQ4Amt()));
            if (sum.compareTo(budget.getTotalAmt()) != 0) {
                throw new ServiceException(422, "四季分解合计 " + strip(sum)
                        + " 与年度总额 " + strip(budget.getTotalAmt()) + " 不一致");
            }
        }
        RebateBudget exist = budgetDao.selectOne(new LambdaQueryWrapper<RebateBudget>()
                .eq(RebateBudget::getBudgetYear, budget.getBudgetYear()));
        if (exist != null) {
            budget.setId(exist.getId());
            budget.setVerNo(exist.getVerNo());
            budgetDao.updateById(budget);
            return budget;
        }
        budget.setId(uuid());
        budget.setCreateBy(currentUser());
        budgetDao.insert(budget);
        return budget;
    }

    @Override
    public Map<String, Object> budgetSummary(int year, String quarter) {
        requireAny("查询返利预算");
        RebateBudget b = budgetDao.selectOne(new LambdaQueryWrapper<RebateBudget>()
                .eq(RebateBudget::getBudgetYear, year));
        BigDecimal budget = BigDecimal.ZERO;
        if (b != null && quarter != null) {
            switch (quarter.substring(5)) {
                case "Q1" -> budget = nvl(b.getQ1Amt());
                case "Q2" -> budget = nvl(b.getQ2Amt());
                case "Q3" -> budget = nvl(b.getQ3Amt());
                default -> budget = nvl(b.getQ4Amt());
            }
        }
        BigDecimal consumed = settleDao.selectQuarterConsumed(quarter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("quarter", quarter);
        out.put("budget", budget);
        out.put("consumed", consumed);
        out.put("remain", budget.subtract(consumed));
        out.put("budgetRow", b);
        return out;
    }

    // ==================== 计算（11.3/11.4/11.5） ====================

    @Override
    @Transactional
    public Map<String, Object> calculate(String customerId, String quarter) {
        requireAny("返利计算");
        if (isBlank(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        var m = QUARTER.matcher(quarter == null ? "" : quarter);
        if (!m.matches()) {
            throw new ServiceException(422, "季度格式应为 yyyyQn，如 2026Q4");
        }
        int year = Integer.parseInt(m.group(1));
        int q = Integer.parseInt(m.group(2));
        LocalDate from = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        LocalDate to = from.plusMonths(3).minusDays(1);

        // 同客户同季度唯一：审批中/已批/已执行不可重算
        RebateSettlement exist = settleDao.selectOne(new LambdaQueryWrapper<RebateSettlement>()
                .eq(RebateSettlement::getCustomerId, customerId)
                .eq(RebateSettlement::getQuarter, quarter));
        if (exist != null && !RebateSettlement.ST_DRAFT.equals(exist.getStatus())
                && !RebateSettlement.ST_REJECTED.equals(exist.getStatus())) {
            throw new ServiceException(422, "本季结算单已提交（" + exist.getSettleNo()
                    + "，状态 " + exist.getStatus() + "），不可重算");
        }

        // ---- 三配置校验：缺失即阻断（FR-4.3-8-2 / 11.2） ----
        RebateTarget target = targetDao.selectOne(new LambdaQueryWrapper<RebateTarget>()
                .eq(RebateTarget::getCustomerId, customerId)
                .eq(RebateTarget::getQuarter, quarter));
        if (target == null) {
            throw new ServiceException(422, "本季季度销售目标未维护，请先在配置 Tab 维护目标");
        }
        List<RebatePolicy> all = policyDao.selectEffective(customerId, LocalDate.now());
        List<RebatePolicy> bands = new ArrayList<>();
        for (RebatePolicy p : all) {
            if (customerId.equals(p.getCustomerId())) {
                bands.add(p);
            }
        }
        if (bands.isEmpty()) {
            for (RebatePolicy p : all) {
                if (p.getCustomerId() == null) {
                    bands.add(p);
                }
            }
        }
        if (bands.isEmpty()) {
            throw new ServiceException(422, "生效返利政策未维护，请先在配置 Tab 维护政策阶梯");
        }
        bands.sort((a, b) -> a.getBandFrom().compareTo(b.getBandFrom()));
        RebateBudget budget = budgetDao.selectOne(new LambdaQueryWrapper<RebateBudget>()
                .eq(RebateBudget::getBudgetYear, year));
        if (budget == null) {
            throw new ServiceException(422, year + " 年度返利预算未维护，请先在配置 Tab 维护预算");
        }

        MdmCustomerGroup cust = customerDao.selectById(customerId);
        if (cust == null) {
            throw new ServiceException(404, "客户不存在：" + customerId);
        }

        RebateSettlement s = exist != null ? exist : new RebateSettlement();
        s.setCustomerId(customerId);
        s.setCustomerCode(cust.getCustomerCode());
        s.setCustomerName(cust.getCustomerName());
        s.setQuarter(quarter);
        s.setTargetAmt(nvl(target.getTargetAmt()));

        // ---- 数据不完整 → 延期不出结果（FR-4.3-8-1） ----
        List<SdReturn> pending = returnDao.selectList(new LambdaQueryWrapper<SdReturn>()
                .eq(SdReturn::getCustomerId, customerId)
                .ge(SdReturn::getApplyAt, from.atStartOfDay())
                .le(SdReturn::getApplyAt, to.atTime(23, 59, 59))
                .notIn(SdReturn::getStatus, RETURN_TERMINAL));
        if (!pending.isEmpty()) {
            s.setPendingData("1");
            s.setPendingHint("存在未完成退货单：" + pending.get(0).getReturnNo()
                    + (pending.size() > 1 ? " 等 " + pending.size() + " 单" : ""));
            s.setRebateAmt(BigDecimal.ZERO);
            s.setBaseAmt(BigDecimal.ZERO);
            s.setAchieveRate(BigDecimal.ZERO);
            s.setStatus(RebateSettlement.ST_DRAFT);
            persist(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settlement", s);
            out.put("pending", true);
            out.put("pendingDocs", pending);
            return out;
        }

        // ---- 达成率：基数 = 开票确认额 − 退货退款额（红字冲减） ----
        BigDecimal base = nvl(arDao.selectQuarterBase(customerId, from, to));
        BigDecimal rate = s.getTargetAmt().signum() <= 0 ? BigDecimal.ZERO
                : base.divide(s.getTargetAmt(), 4, RoundingMode.HALF_UP);

        // ---- 超额累进分段（FR-4.3-8-2：仅对落入阶梯的超额部分计返利） ----
        BigDecimal ratePct = rate.multiply(new BigDecimal("100"));
        List<Map<String, Object>> segs = new ArrayList<>();
        BigDecimal rebate = BigDecimal.ZERO;
        for (RebatePolicy band : bands) {
            BigDecimal lo = band.getBandFrom();
            // 上限空 = 无上限段：用达成率本身封顶（落段 = lo → rate）
            BigDecimal hi = band.getBandTo() == null ? ratePct : band.getBandTo().min(ratePct);
            if (hi.compareTo(lo) <= 0) {
                continue;
            }
            // 段金额 = 季度目标 × 段宽（达成率百分点 / 100） × 段返利率
            BigDecimal segAmt = s.getTargetAmt()
                    .multiply(hi.subtract(lo)).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                    .multiply(band.getRebateRate()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("bandFrom", lo);
            seg.put("bandTo", band.getBandTo());
            seg.put("usedTo", hi);
            seg.put("rebateRate", band.getRebateRate());
            seg.put("bandBase", s.getTargetAmt().multiply(hi.subtract(lo))
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            seg.put("amount", segAmt);
            segs.add(seg);
            rebate = rebate.add(segAmt);
        }

        // ---- 预算校验（FR-4.3-8-3 / C-4.3-05） ----
        String quarterKey = "Q" + q;
        BigDecimal budgetAmt = switch (quarterKey) {
            case "Q1" -> nvl(budget.getQ1Amt());
            case "Q2" -> nvl(budget.getQ2Amt());
            case "Q3" -> nvl(budget.getQ3Amt());
            default -> nvl(budget.getQ4Amt());
        };
        BigDecimal consumed = settleDao.selectQuarterConsumed(quarter);
        if (exist != null && !RebateSettlement.ST_DRAFT.equals(exist.getStatus())
                && !RebateSettlement.ST_REJECTED.equals(exist.getStatus())) {
            // 不可能到这里（前置已拦），保底
            consumed = consumed.subtract(nvl(exist.getRebateAmt()));
        }
        BigDecimal remain = budgetAmt.subtract(consumed);
        boolean over = rebate.compareTo(remain) > 0;

        s.setBaseAmt(base);
        s.setAchieveRate(rate);
        s.setRebateAmt(rebate);
        s.setBudgetAmt(budgetAmt);
        s.setBudgetRemain(remain);
        s.setOverBudget(over ? "1" : "0");
        s.setSegments(writeJson(segs));
        s.setPendingData("0");
        s.setPendingHint(null);
        if (!over) {
            // 超预算清空旧说明（重新计算后不再超预算时允许走正常档）
            s.setOverReason(null);
            s.setBalancePlan(null);
        }
        s.setStatus(RebateSettlement.ST_DRAFT);
        persist(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("segments", segs);
        out.put("overBudget", over);
        out.put("pending", false);
        return out;
    }

    @Override
    public Page<RebateSettlement> page(long current, long size, String customerId,
                                       String quarter, String status) {
        requireAny("查询返利结算");
        LambdaQueryWrapper<RebateSettlement> qw = new LambdaQueryWrapper<RebateSettlement>()
                .eq(isNotBlank(customerId), RebateSettlement::getCustomerId, customerId)
                .eq(isNotBlank(quarter), RebateSettlement::getQuarter, quarter)
                .eq(isNotBlank(status), RebateSettlement::getStatus, status)
                .orderByDesc(RebateSettlement::getCreateDate);
        return settleDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireAny("查询返利结算");
        RebateSettlement s = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("segments", readJson(s.getSegments()));
        if (isNotBlank(s.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(s.getApprovalId()));
        }
        out.put("approvalLogs", approvalEngine.logs("Rebate", s.getId()));
        // 执行后：冲抵核销明细 + 结算凭证（3.9.3 兑现页查看）
        if (isNotBlank(s.getVoucherId())) {
            try {
                out.put("writeoffs", writeoffDao.selectBySettle(s.getSettleNo()));
                out.put("voucher", voucherService.detail(s.getVoucherId()));
            } catch (Exception e) {
                log.warn("rebate {} detail enrich failed: {}", s.getSettleNo(), e.getMessage());
            }
        }
        return out;
    }

    // ==================== 审批（11.6） ====================

    @Override
    @Transactional
    public RebateSettlement submit(String id, String overReason, String balancePlan) {
        requireAny("提交返利审批");
        RebateSettlement s = require(id);
        if (!RebateSettlement.ST_DRAFT.equals(s.getStatus())
                && !RebateSettlement.ST_REJECTED.equals(s.getStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可提交：" + s.getStatus());
        }
        if ("1".equals(s.getPendingData())) {
            throw new ServiceException(422, "数据不完整待补齐，暂不可提交（"
                    + (s.getPendingHint() == null ? "" : s.getPendingHint()) + "）");
        }
        boolean over = "1".equals(s.getOverBudget());
        if (over) {
            // C-4.3-05：超预算须附超预算说明与年度预算平衡方案
            if (isBlank(overReason) || overReason.trim().length() < 2) {
                throw new ServiceException(422, "超季度预算须填写超预算原因说明（C-4.3-05）");
            }
            if (isBlank(balancePlan) || balancePlan.trim().length() < 2) {
                throw new ServiceException(422, "超季度预算须填写年度预算平衡方案（C-4.3-05）");
            }
            s.setOverReason(overReason.trim());
            s.setBalancePlan(balancePlan.trim());
        }

        List<List<ApprovalNodeSpec>> chain;
        if (over) {
            // 升级：销售主管 + 销售总监两级（L2 强制审批）
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售主管审批")),
                    List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批（超预算）")));
        } else {
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售主管审批")));
        }
        String title = "返利结算审批：" + s.getSettleNo() + " / " + s.getCustomerName()
                + " / " + s.getQuarter() + " / 返利 " + strip(s.getRebateAmt())
                + (over ? " / 超季度预算升级总监" : "");
        var inst = approvalEngine.submit("Rebate", s.getId(), title, null, chain);
        s.setApprovalId(inst.getId());
        s.setStatus(RebateSettlement.ST_APPROVING);
        settleDao.updateById(s);
        log.info("rebate {} submitted (over={})", s.getSettleNo(), over);
        return s;
    }

    // ==================== 执行与凭证（11.7/11.8） ====================

    @Override
    @Transactional
    public Map<String, Object> execute(String id, String execType, LocalDate execDate,
                                       String confirmBy, String confirmNote) {
        requireAny("返利执行");
        RebateSettlement s = require(id);
        if (!RebateSettlement.ST_APPROVED.equals(s.getStatus())) {
            throw new ServiceException(422, "仅审批通过的结算单可执行：" + s.getStatus());
        }
        if (!RebateSettlement.EXEC_OFFSET.equals(execType)
                && !RebateSettlement.EXEC_CASH.equals(execType)) {
            throw new ServiceException(422, "执行方式 OFFSET/CASH 必选");
        }
        if (isBlank(confirmBy)) {
            throw new ServiceException(422, "客户确认方式必填（留痕 FR-4.3-8-5）");
        }
        LocalDate date = execDate == null ? LocalDate.now() : execDate;
        BigDecimal amount = nvl(s.getRebateAmt());
        if (amount.signum() <= 0) {
            throw new ServiceException(422, "返利金额为 0，无需执行");
        }

        List<String> writeoffIds = new ArrayList<>();
        String accountCredit;
        if (RebateSettlement.EXEC_OFFSET.equals(execType)) {
            // ---- 应付冲抵：FIFO 冲抵未清应收 ----
            List<ArInvoice> pool = arDao.selectFifoPool(s.getCustomerId());
            BigDecimal open = BigDecimal.ZERO;
            for (ArInvoice ar : pool) {
                open = open.add(balanceOf(ar));
            }
            if (open.compareTo(amount) < 0) {
                throw new ServiceException(422, "未清应收余额不足冲抵（可用 " + strip(open)
                        + " < 返利 " + strip(amount) + "），请改用现金兑现");
            }
            BigDecimal remain = amount;
            for (ArInvoice ar : pool) {
                if (remain.signum() <= 0) {
                    break;
                }
                BigDecimal bal = balanceOf(ar);
                if (bal.signum() <= 0) {
                    continue;
                }
                BigDecimal take = bal.min(remain);
                // 应收回写
                ar.setPaidAmount(nvl(ar.getPaidAmount()).add(take));
                BigDecimal after = balanceOf(ar);
                ar.setStatus(after.signum() <= 0 ? ArInvoice.ST_PAID
                        : (ar.getPaidAmount().signum() > 0 ? ArInvoice.ST_PARTIAL : ArInvoice.ST_UNPAID));
                arDao.updateById(ar);
                // 核销记录（类型 REBATE，凭证统一挂结算凭证）
                ArWriteoff wo = new ArWriteoff();
                wo.setId(uuid());
                wo.setWoNo(nextNo("WO", writeoffDao::selectNosByPrefix));
                wo.setArId(ar.getId());
                wo.setArNo(ar.getArNo());
                wo.setCustomerId(ar.getCustomerId());
                wo.setAmount(take);
                wo.setPayDate(date);
                wo.setDueDate(ar.getDueDate());
                wo.setOnTime(ar.getDueDate() != null && !date.isAfter(ar.getDueDate()) ? "1" : "0");
                wo.setWriteType("REBATE");
                wo.setRemark("返利冲抵 " + s.getSettleNo());
                wo.setCreateBy(currentUser());
                writeoffDao.insert(wo);
                writeoffIds.add(wo.getId());
                remain = remain.subtract(take);
            }
            accountCredit = "1122";
            s.setOffsetAmt(amount);
        } else {
            // ---- 现金兑现登记：贷 1002 银行存款 ----
            accountCredit = "1002";
            s.setCashAmt(amount);
        }

        // ---- 结算凭证：借 6601 销售费用（返利）/ 贷 应收或银行 ----
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of("6601", "DR", amount,
                "返利结算 " + s.getSettleNo() + " " + s.getQuarter()));
        lines.add(GlVoucherService.FinVoucherLineSpec.of(accountCredit, "CR", amount,
                RebateSettlement.EXEC_OFFSET.equals(execType)
                        ? "返利冲抵应收 " + s.getCustomerName()
                        : "返利现金兑现 " + s.getCustomerName()));
        FinVoucher v = voucherService.create("REB", date,
                "返利结算凭证 " + s.getSettleNo() + " " + strip(amount),
                "REBATE", s.getSettleNo(), null, lines);
        s.setVoucherId(v.getId());
        s.setVoucherNo(v.getVoucherNo());
        for (String woId : writeoffIds) {
            ArWriteoff wo = writeoffDao.selectById(woId);
            if (wo != null) {
                wo.setVoucherId(v.getId());
                writeoffDao.updateById(wo);
            }
        }

        s.setExecType(execType);
        s.setExecAt(LocalDateTime.now());
        s.setExecBy(currentUser());
        s.setConfirmBy(confirmBy);
        s.setConfirmNote(confirmNote);
        s.setStatus(RebateSettlement.ST_EXECUTED);
        settleDao.updateById(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlement", s);
        out.put("voucherNo", v.getVoucherNo());
        out.put("writeoffCount", writeoffIds.size());
        log.info("rebate {} executed {} voucher {}", s.getSettleNo(), execType, v.getVoucherNo());
        return out;
    }

    // ==================== 私有工具 ====================

    private void persist(RebateSettlement s) {
        if (s.getId() == null) {
            s.setId(uuid());
            s.setSettleNo(nextNo("RB", settleDao::selectNosByPrefix));
            s.setCreateBy(currentUser());
            settleDao.insert(s);
        } else {
            settleDao.updateById(s);
        }
    }

    private RebateSettlement require(String id) {
        RebateSettlement s = settleDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "返利结算单不存在：" + id);
        }
        return s;
    }

    private void requireAny(String action) {
        String user = currentUser();
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean allowed = auth.getAuthorities().stream()
                .anyMatch(a -> {
                    String r = a.getAuthority();
                    return r.startsWith("ROLE_") && List.of(ROLES).contains(r);
                });
        if (!allowed) {
            throw new ServiceException(403, "无权限" + action + "（" + user + "）");
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    private String nextNo(String kind, Function<String, List<String>> query) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : query.apply(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 跳过非规范编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private static BigDecimal balanceOf(ArInvoice ar) {
        return nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                .subtract(nvl(ar.getPaidAmount()));
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(500, "分段明细序列化失败：" + e.getMessage());
        }
    }

    private Object readJson(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ApPlanDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPaymentRequestDao;
import com.erp.dao.fin.FinPrepaymentDao;
import com.erp.entity.fin.ApPlan;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.entity.fin.FinPrepayment;
import com.erp.service.SysParamService;
import com.erp.service.fin.ApAgingService;
import com.erp.service.fin.ApForecastService;
import com.erp.service.fin.ApPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应付管理（add-ap-management：8.3.2 账龄 / 8.3.3 付款计划闭环 / 资金预测）。
 *
 * 账龄复用 AR_AGING_BUCKETS 六段参数（与应收账龄口径对称，零新参数）；
 * 基数=POSTED 且未清（TOTAL−PAID）>0 的应付，按 DUE_DATE 落段（NULL 到期日跳过并计数）。
 * 计划 PERIOD+LEGAL_ENTITY 唯一，超计划付款阻断（design D4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApAgingServiceImpl implements ApAgingService, ApPlanService, ApForecastService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FinApInvoiceDao apInvoiceDao;
    private final ApPlanDao planDao;
    private final FinPaymentRequestDao paymentRequestDao;
    private final FinPrepaymentDao prepaymentDao;
    private final FinBankAccountDao bankAccountDao;
    private final SysParamService sysParamService;
    private final com.erp.dao.mdm.MdmSupplierDao supplierDao;

    // ================= 8.3.2 应付账龄 =================

    @Override
    public Map<String, Object> aging(String supplierId, LocalDate baseDate) {
        if (baseDate == null) {
            baseDate = LocalDate.now();
        }
        List<Integer> bounds = bucketBounds();
        int n = bounds.size() + 1;
        List<FinApInvoice> open = openPayable(supplierId);

        BigDecimal[] curr = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            curr[i] = BigDecimal.ZERO;
        }
        Map<String, Map<String, BigDecimal>> supBucket = new LinkedHashMap<>();
        Map<String, String> supName = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int nullDue = 0;
        for (FinApInvoice inv : open) {
            if (inv.getDueDate() == null) {
                nullDue++;   // 存量未补录到期日，跳过避免错误账龄（design 风险）
                continue;
            }
            BigDecimal bal = unpaidOf(inv);
            int b = bucketIndex(ChronoUnit.DAYS.between(inv.getDueDate(), baseDate), bounds);
            curr[b] = curr[b].add(bal);
            total = total.add(bal);
            supBucket.computeIfAbsent(inv.getSupplierId(), k -> {
                Map<String, BigDecimal> init = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    init.put(String.valueOf(i), BigDecimal.ZERO);
                }
                return init;
            });
            Map<String, BigDecimal> sb = supBucket.get(inv.getSupplierId());
            sb.put(String.valueOf(b), sb.get(String.valueOf(b)).add(bal));
            supName.putIfAbsent(inv.getSupplierId(), inv.getSupplierName());
        }

        // 超期（overdue）= 落在第一个边界之后的段（逾期>首边界天数）
        int overdueFrom = 1;
        BigDecimal overdue = BigDecimal.ZERO;
        for (int i = overdueFrom; i < n; i++) {
            overdue = overdue.add(curr[i]);
        }
        BigDecimal overdueRatio = total.signum() == 0 ? BigDecimal.ZERO
                : overdue.divide(total, 4, RoundingMode.HALF_UP);

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("label", bucketLabel(i, bounds));
            b.put("base", curr[i].setScale(2, RoundingMode.HALF_UP));
            b.put("ratio", total.signum() == 0 ? BigDecimal.ZERO
                    : curr[i].divide(total, 4, RoundingMode.HALF_UP));
            buckets.add(b);
        }

        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> e : supBucket.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("supplierId", e.getKey());
            c.put("supplierName", supName.get(e.getKey()));
            BigDecimal sTotal = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                sTotal = sTotal.add(e.getValue().get(String.valueOf(i)));
            }
            c.put("total", sTotal.setScale(2, RoundingMode.HALF_UP));
            c.put("buckets", e.getValue());
            suppliers.add(c);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseDate", baseDate.toString());
        out.put("total", total.setScale(2, RoundingMode.HALF_UP));
        out.put("overdue", overdue.setScale(2, RoundingMode.HALF_UP));
        out.put("overdueRatio", overdueRatio);
        out.put("buckets", buckets);
        out.put("suppliers", suppliers);
        out.put("nullDueCount", nullDue);
        return out;
    }

    // ================= 8.3.3 付款计划闭环 =================

    @Override
    @Transactional
    public Map<String, Object> create(ApPlan req) {
        if (req == null || !hasText(req.getPeriod()) || req.getPeriod().length() != 6) {
            throw new ServiceException(422, "期间（yyyyMM）必填");
        }
        if (req.getPlanTotal() == null || req.getPlanTotal().signum() <= 0) {
            throw new ServiceException(422, "计划总额必须大于 0");
        }
        // PERIOD+LEGAL_ENTITY 唯一（legalEntityId 可空=全法人）
        Long dup = planDao.selectCount(new LambdaQueryWrapper<ApPlan>()
                .eq(ApPlan::getPeriod, req.getPeriod())
                .eq(req.getLegalEntityId() != null && !req.getLegalEntityId().isBlank(),
                        ApPlan::getLegalEntityId, req.getLegalEntityId())
                .isNull(req.getLegalEntityId() == null || req.getLegalEntityId().isBlank(),
                        ApPlan::getLegalEntityId)
                .eq(ApPlan::getDelFlag, "0"));
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "期间 " + req.getPeriod() + " 该法人已存在付款计划，不可重复创建");
        }
        ApPlan p = new ApPlan();
        p.setId(uuid());
        p.setPlanNo(nextPlanNo(req.getPeriod()));
        p.setPeriod(req.getPeriod());
        p.setLegalEntityId(hasText(req.getLegalEntityId()) ? req.getLegalEntityId() : null);
        p.setPlanTotal(req.getPlanTotal().setScale(2, RoundingMode.HALF_UP));
        p.setExecutedTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        p.setPlanLines(req.getPlanLines());
        p.setStatus(ApPlan.ST_DRAFT);
        p.setRemark(req.getRemark());
        p.setCreateBy(com.erp.util.SecurityUtils.getCurrentUserId());
        planDao.insert(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", p.getId());
        out.put("planNo", p.getPlanNo());
        out.put("status", p.getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        ApPlan p = require(id);
        if (!ApPlan.ST_DRAFT.equals(p.getStatus()) && !ApPlan.ST_CLOSED.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿/被驳回可提交审批（当前 " + p.getStatus() + "）");
        }
        // wrapper 显式更新（BaseEntity @Version：updateById(patch) 因 patch.verNo=0 致 0 行更新）
        planDao.update(null, new LambdaUpdateWrapper<ApPlan>()
                .eq(ApPlan::getId, p.getId())
                .set(ApPlan::getStatus, ApPlan.ST_PENDING));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", p.getId());
        out.put("status", ApPlan.ST_PENDING);
        return out;
    }

    @Override
    @Transactional
    public void onApprovalResult(String id, boolean approved, String reason) {
        ApPlan p = require(id);
        LambdaUpdateWrapper<ApPlan> uw = new LambdaUpdateWrapper<ApPlan>()
                .eq(ApPlan::getId, p.getId());
        if (approved) {
            uw.set(ApPlan::getStatus, ApPlan.ST_APPROVED);
        } else {
            uw.set(ApPlan::getStatus, ApPlan.ST_DRAFT)
              .set(ApPlan::getRejectReason, reason);
        }
        planDao.update(null, uw);
    }

    @Override
    @Transactional
    public Map<String, Object> close(String id) {
        ApPlan p = require(id);
        if (!ApPlan.ST_APPROVED.equals(p.getStatus())) {
            throw new ServiceException(422, "仅已批准计划可关闭（当前 " + p.getStatus() + "）");
        }
        planDao.update(null, new LambdaUpdateWrapper<ApPlan>()
                .eq(ApPlan::getId, p.getId())
                .set(ApPlan::getStatus, ApPlan.ST_CLOSED));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", p.getId());
        out.put("status", ApPlan.ST_CLOSED);
        return out;
    }

    @Override
    public Page<ApPlan> page(long current, long size, String period, String status) {
        LambdaQueryWrapper<ApPlan> w = new LambdaQueryWrapper<ApPlan>()
                .eq(ApPlan::getDelFlag, "0")
                .eq(hasText(period), ApPlan::getPeriod, period)
                .eq(hasText(status), ApPlan::getStatus, status)
                .orderByDesc(ApPlan::getPeriod);
        return planDao.selectPage(new Page<>(current, size), w);
    }

    @Override
    public Map<String, Object> detail(String id) {
        ApPlan p = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        try {
            out.put("lines", MAPPER.readValue(p.getPlanLines(), List.class));
        } catch (Exception ignore) {
            out.put("lines", List.of());
        }
        return out;
    }

    @Override
    public void assertWithinPlan(String period, BigDecimal amount) {
        ApPlan plan = planDao.selectApproved(period, null);
        if (plan == null) {
            return;   // 无计划不阻断
        }
        BigDecimal executed = nz(plan.getExecutedTotal());
        BigDecimal total = nz(plan.getPlanTotal());
        if (executed.add(amount).compareTo(total) > 0) {
            throw new ServiceException(422, "超付款计划：当期计划 " + total
                    + " 已执行 " + executed + "，本次 " + amount + " 将超计划，须先追加计划额度");
        }
    }

    @Override
    @Transactional
    public void addExecuted(String period, BigDecimal amount) {
        ApPlan plan = planDao.selectApproved(period, null);
        if (plan == null) {
            return;
        }
        // 原子自增（wrapper setSql，绕开 @Version 且防并发丢更新）
        planDao.update(null, new LambdaUpdateWrapper<ApPlan>()
                .eq(ApPlan::getId, plan.getId())
                .setSql("EXECUTED_TOTAL = COALESCE(EXECUTED_TOTAL,0) + " + amount.toPlainString()));
    }

    // ================= 资金预测 =================

    @Override
    public Map<String, Object> forecast(LocalDate baseDate) {
        if (baseDate == null) {
            baseDate = LocalDate.now();
        }
        // 银行可用余额（ACTIVE 汇总）
        BigDecimal bankTotal = BigDecimal.ZERO;
        for (FinBankAccount acct : bankAccountDao.selectList(new LambdaQueryWrapper<FinBankAccount>()
                .eq(FinBankAccount::getStatus, "ACTIVE")
                .eq(FinBankAccount::getDelFlag, "0"))) {
            bankTotal = bankTotal.add(nz(acct.getBalance()));
        }

        // 未清应付（POSTED 且未清>0）
        List<FinApInvoice> open = apInvoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getStatus, "POSTED")
                .eq(FinApInvoice::getDelFlag, "0")
                .isNotNull(FinApInvoice::getDueDate));

        // 已排期待付（SCHEDULED 且 PLAN_DATE 非空）
        List<FinPaymentRequest> scheduled = paymentRequestDao.selectList(
                new LambdaQueryWrapper<FinPaymentRequest>()
                        .eq(FinPaymentRequest::getStatus, FinPaymentRequest.ST_SCHEDULED)
                        .eq(FinPaymentRequest::getDelFlag, "0")
                        .isNotNull(FinPaymentRequest::getPlanDate));

        // 预付款占用（已执行未冲抵 = EXECUTED − SETTLED）
        List<FinPrepayment> prepayOccupied = prepaymentDao.selectList(
                new LambdaQueryWrapper<FinPrepayment>()
                        .eq(FinPrepayment::getDelFlag, "0")
                        .ne(FinPrepayment::getStatus, "CANCELLED"));

        List<Map<String, Object>> windows = new ArrayList<>();
        for (int days : new int[]{7, 30, 90}) {
            LocalDate horizon = baseDate.plusDays(days);
            BigDecimal apDue = BigDecimal.ZERO;
            for (FinApInvoice inv : open) {
                // 累积口径：含逾期（dueDate ≤ horizon）——逾期是最紧迫现金义务，资金预测须计入
                if (!inv.getDueDate().isAfter(horizon)) {
                    apDue = apDue.add(unpaidOf(inv));
                }
            }
            BigDecimal sched = BigDecimal.ZERO;
            for (FinPaymentRequest r : scheduled) {
                // 累积口径：PLAN_DATE ≤ horizon（含已排期逾期）
                if (r.getPlanDate() != null && !r.getPlanDate().isAfter(horizon)) {
                    sched = sched.add(nz(r.getApplyAmount()).subtract(nz(r.getExecutedAmount())));
                }
            }
            BigDecimal prepay = BigDecimal.ZERO;
            for (FinPrepayment pp : prepayOccupied) {
                prepay = prepay.add(nz(pp.getExecutedAmount()).subtract(nz(pp.getSettledAmount())));
            }
            BigDecimal need = apDue.add(sched).add(prepay);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("days", days);
            w.put("apDue", apDue.setScale(2, RoundingMode.HALF_UP));
            w.put("scheduled", sched.setScale(2, RoundingMode.HALF_UP));
            w.put("prepay", prepay.setScale(2, RoundingMode.HALF_UP));
            w.put("need", need.setScale(2, RoundingMode.HALF_UP));
            w.put("bank", bankTotal.setScale(2, RoundingMode.HALF_UP));
            w.put("gap", bankTotal.subtract(need).setScale(2, RoundingMode.HALF_UP));
            windows.add(w);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseDate", baseDate.toString());
        out.put("bankTotal", bankTotal.setScale(2, RoundingMode.HALF_UP));
        out.put("windows", windows);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> backfillDueDate() {
        List<FinApInvoice> nulls = apInvoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getDelFlag, "0")
                .isNull(FinApInvoice::getDueDate));
        int updated = 0;
        int skipped = 0;
        for (FinApInvoice inv : nulls) {
            com.erp.entity.mdm.MdmSupplier sup = supplierDao.selectById(inv.getSupplierId());
            String terms = sup == null ? null : sup.getPaymentTerms();
            if (!hasText(terms) || !terms.trim().matches("\\d+") || inv.getInvoiceDate() == null) {
                skipped++;
                continue;
            }
            apInvoiceDao.update(null, new LambdaUpdateWrapper<FinApInvoice>()
                    .eq(FinApInvoice::getId, inv.getId())
                    .set(FinApInvoice::getDueDate,
                            inv.getInvoiceDate().plusDays(Integer.parseInt(terms.trim()))));
            updated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("skippedNoTerms", skipped);
        return out;
    }

    // ================= 私有助手 =================

    /** 未清应付（POSTED 且未清>0），NULL 到期日在 aging 内跳过 */
    private List<FinApInvoice> openPayable(String supplierId) {
        List<FinApInvoice> all = apInvoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getStatus, "POSTED")
                .eq(FinApInvoice::getDelFlag, "0")
                .eq(hasText(supplierId), FinApInvoice::getSupplierId, supplierId));
        List<FinApInvoice> out = new ArrayList<>();
        for (FinApInvoice inv : all) {
            if (unpaidOf(inv).signum() > 0) {
                out.add(inv);
            }
        }
        return out;
    }

    private BigDecimal unpaidOf(FinApInvoice inv) {
        return nz(inv.getTotalAmount()).subtract(nz(inv.getPaidAmount()));
    }

    private List<Integer> bucketBounds() {
        String raw = sysParamService.getValue("AR_AGING_BUCKETS", "30,60,90,180,360");
        List<Integer> bounds = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                bounds.add(Integer.parseInt(t));
            }
        }
        if (bounds.isEmpty()) {
            throw new ServiceException(500, "AR_AGING_BUCKETS 参数无效");
        }
        return bounds;
    }

    private int bucketIndex(long days, List<Integer> bounds) {
        if (days <= 0) {
            return 0;
        }
        for (int i = 0; i < bounds.size(); i++) {
            if (days <= bounds.get(i)) {
                return i;
            }
        }
        return bounds.size();
    }

    private String bucketLabel(int idx, List<Integer> bounds) {
        if (idx == 0) {
            return "0-" + bounds.get(0);
        }
        if (idx == bounds.size()) {
            return bounds.get(bounds.size() - 1) + "+";
        }
        return (bounds.get(idx - 1) + 1) + "-" + bounds.get(idx);
    }

    private ApPlan require(String id) {
        ApPlan p = planDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "付款计划不存在：" + id);
        }
        return p;
    }

    private String nextPlanNo(String period) {
        String base = "PPL" + period;
        String prefix = base + "-%";
        int max = 0;
        for (String no : planDao.selectNosByPrefix(prefix)) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(base.length() + 1)));
            } catch (Exception ignore) {
                // 跳过非数字后缀
            }
        }
        return base + "-" + String.format("%02d", max + 1);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

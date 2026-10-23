package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.AmortPlanDao;
import com.erp.dao.fin.DeferredDocDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPeriodDao;
import com.erp.dao.fin.PrepaidDocDao;
import com.erp.entity.fin.AmortPlan;
import com.erp.entity.fin.DeferredDoc;
import com.erp.entity.fin.FinAccount;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPeriod;
import com.erp.entity.fin.PrepaidDoc;
import com.erp.service.SysParamService;
import com.erp.service.fin.AmortService;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
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

/**
 * 预提待摊实现（M8-9，spec prepaid-amortization）。
 * 决策⑦：计划表落库预提/待摊共用；决策⑧：手动触发+幂等+DRAFT 三态+结账⑨；
 * 决策⑨：年末检查 L4 页面黄标；差异行恒生成保借贷平衡（>0.5% 摘要标记 C-4.6-16）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmortServiceImpl implements AmortService {

    public static final String BIZ_PREPAY = "PREPAY";
    public static final String BIZ_AMORT = "AMORT";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final PrepaidDocDao prepaidDao;
    private final DeferredDocDao deferredDao;
    private final AmortPlanDao planDao;
    private final FinAccountDao accountDao;
    private final FinBalanceDao balanceDao;
    private final FinPeriodDao periodDao;
    private final FinBankAccountDao bankDao;
    private final SysParamService sysParamService;
    private final GlVoucherService voucherService;

    // ================================================================
    // 4.1 登记与计划生成
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> registerPrepaid(Map<String, Object> payload) {
        requireFinance("登记预提费用");
        String accountCode = str(payload.get("expenseAccountCode")).trim();
        String period = str(payload.get("period")).trim();
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "预提金额必须大于 0");
        }
        if (!period.matches("\\d{6}")) {
            throw new ServiceException(422, "归属期间格式须为 yyyyMM");
        }
        FinAccount acct = requireExpenseAccount(accountCode);

        PrepaidDoc doc = new PrepaidDoc();
        doc.setId(uuid());
        doc.setPreNo(nextNo("PRE", prepaidDao::selectMaxSeq));
        doc.setExpenseType(str(payload.get("expenseType")));
        doc.setAccountCode(acct.getAccountCode());
        doc.setAccountName(acct.getAccountName());
        doc.setPeriod(period);
        doc.setAmount(amount);
        doc.setStatus(PrepaidDoc.ST_OPEN);
        doc.setRemark(str(payload.get("remark")));
        doc.setCreateBy(currentUser());
        doc.setCreateDate(LocalDateTime.now());
        doc.setVerNo(0);
        prepaidDao.insert(doc);

        // 单期计提计划行（预提 = 归属期一次计提）
        insertPlan(AmortPlan.TYPE_PREPAY, doc.getId(), doc.getPreNo(), period, amount);
        assertPlanTotal(doc.getId(), amount); // L1 合计=登记总额（FR-4.6-8-2）

        log.info("prepaid {} registered: {} {} by {}", doc.getPreNo(), amount, period, currentUser());
        return docRow(doc);
    }

    @Override
    @Transactional
    public Map<String, Object> registerDeferred(Map<String, Object> payload) {
        requireFinance("登记待摊费用");
        String summary = str(payload.get("summary")).trim();
        BigDecimal total = dec(payload.get("totalAmount"));
        Integer months = dec(payload.get("amortMonths")) == null ? null
                : dec(payload.get("amortMonths")).intValue();
        String startPeriod = str(payload.get("startPeriod")).trim();
        if (summary.isEmpty()) {
            throw new ServiceException(422, "待摊事项说明必填");
        }
        if (total == null || total.signum() <= 0) {
            throw new ServiceException(422, "待摊总金额必须大于 0");
        }
        if (months == null || months < 1) {
            throw new ServiceException(422, "摊销月数必须 ≥ 1");
        }
        if (!startPeriod.matches("\\d{6}")) {
            throw new ServiceException(422, "起始期间格式须为 yyyyMM");
        }
        FinAccount expenseAcct = requireExpenseAccount(str(payload.get("expenseAccountCode")));
        String assetCode = hasText(str(payload.get("assetAccountCode")))
                ? str(payload.get("assetAccountCode")) : "1801";

        DeferredDoc doc = new DeferredDoc();
        doc.setId(uuid());
        doc.setDefNo(nextNo("DEF", deferredDao::selectMaxSeq));
        doc.setSummary(summary);
        doc.setTotalAmount(total);
        doc.setAmortMonths(months);
        doc.setStartPeriod(startPeriod);
        doc.setExpenseAccountCode(expenseAcct.getAccountCode());
        doc.setExpenseAccountName(expenseAcct.getAccountName());
        doc.setAssetAccountCode(assetCode);
        doc.setStatus(DeferredDoc.ST_OPEN);
        doc.setRemark(str(payload.get("remark")));
        doc.setCreateBy(currentUser());
        doc.setCreateDate(LocalDateTime.now());
        doc.setVerNo(0);
        deferredDao.insert(doc);

        // 直线法计划行（FR-4.6-8-2：前 N-1 期月摊销，尾差并入末期）
        BigDecimal per = total.divide(new BigDecimal(months), 2, RoundingMode.FLOOR);
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int y = Integer.parseInt(startPeriod.substring(0, 4));
        int m = Integer.parseInt(startPeriod.substring(4, 6));
        for (int i = 0; i < months; i++) {
            int yy = y + (m - 1 + i) / 12;
            int mm = (m - 1 + i) % 12 + 1;
            String p = String.format("%04d%02d", yy, mm);
            BigDecimal amt = (i == months - 1) ? total.subtract(allocated) : per;
            allocated = allocated.add(amt);
            insertPlan(AmortPlan.TYPE_AMORT, doc.getId(), doc.getDefNo(), p, amt);
        }
        assertPlanTotal(doc.getId(), total); // L1：合计=总额（不平回滚）

        // 支付挂账凭证（业务描述：借 待摊费用 / 贷 银行存款——支付事实自动过账，
        // 与收付执行凭证件同；FR-4.6-8-4 的制单≠审核约束针对计提/摊销凭证）
        var v = voucherService.create("DEFER", lastDayOf(startPeriod),
                "待摊费用挂账：" + doc.getDefNo() + "（" + summary + "）",
                BIZ_AMORT, doc.getDefNo(), null,
                List.of(GlVoucherService.FinVoucherLineSpec.of(assetCode, "DR", total,
                        "待摊费用-" + summary),
                        GlVoucherService.FinVoucherLineSpec.of("1002", "CR", total,
                                "银行存款-待摊支付")));

        // L4 提示（BR-4.6-05，不阻断）
        String l4 = months > 12
                ? "超过 12 个月建议转长期待摊费用核算（BR-4.6-05）" : null;
        log.info("deferred {} registered: {} / {} months from {} l4={} by {}",
                doc.getDefNo(), total, months, startPeriod, l4 != null, currentUser());
        Map<String, Object> out = docRow(doc);
        out.put("voucherId", v.getId());
        out.put("voucherNo", v.getVoucherNo());
        out.put("l4Hint", l4);
        return out;
    }

    // ================================================================
    // 4.2 期末凭证手动触发（幂等）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> generate(String period) {
        requireFinance("生成计提/摊销凭证");
        if (period == null || !period.matches("\\d{6}")) {
            throw new ServiceException(422, "期间格式须为 yyyyMM");
        }
        FinPeriod fp = periodDao.selectByPeriod(period);
        if (fp != null && "CLOSED".equalsIgnoreCase(fp.getStatus())) {
            throw new ServiceException(422, "会计期间已关闭：" + period);
        }
        List<AmortPlan> rows = planDao.selectNotGenerated(period);
        long skipped = planDao.countGenerated(period);
        int generated = 0;
        for (AmortPlan plan : rows) {
            boolean isPrepay = AmortPlan.TYPE_PREPAY.equals(plan.getPlanType());
            String acctDr;
            String acctCr;
            String summary;
            if (isPrepay) {
                PrepaidDoc doc = prepaidDao.selectById(plan.getDocId());
                if (doc == null) {
                    throw new ServiceException(422, "预提单不存在：" + plan.getDocNo());
                }
                acctDr = doc.getAccountCode();
                acctCr = "2251";
                summary = "预提费用计提：" + plan.getDocNo();
            } else {
                DeferredDoc doc = deferredDao.selectById(plan.getDocId());
                if (doc == null) {
                    throw new ServiceException(422, "待摊单不存在：" + plan.getDocNo());
                }
                acctDr = doc.getExpenseAccountCode();
                acctCr = doc.getAssetAccountCode();
                summary = "待摊费用摊销：" + plan.getDocNo() + "（" + doc.getSummary() + "）";
            }
            // DRAFT 凭证（FR-4.6-8-4 制单≠审核，一计划行一凭证 design D6）
            var v = voucherService.create(isPrepay ? BIZ_PREPAY : BIZ_AMORT,
                    lastDayOf(plan.getPeriod()), summary,
                    isPrepay ? BIZ_PREPAY : BIZ_AMORT, plan.getDocNo(), null,
                    List.of(GlVoucherService.FinVoucherLineSpec.of(acctDr, "DR", plan.getAmount(),
                            summary),
                            GlVoucherService.FinVoucherLineSpec.of(acctCr, "CR", plan.getAmount(),
                                    summary)),
                    true);
            // 条件回写（幂等锚：并发/重复触发只处理一次，失败整体回滚）
            int updated = planDao.update(null, new LambdaUpdateWrapper<AmortPlan>()
                    .eq(AmortPlan::getId, plan.getId())
                    .eq(AmortPlan::getGenStatus, AmortPlan.GEN_NOT)
                    .set(AmortPlan::getGenStatus, AmortPlan.GEN_DONE)
                    .set(AmortPlan::getVoucherId, v.getId())
                    .set(AmortPlan::getUpdateBy, currentUser())
                    .set(AmortPlan::getUpdateDate, LocalDateTime.now()));
            if (updated == 0) {
                throw new ServiceException(409, "计划行并发生成冲突：" + plan.getDocNo()
                        + "/" + plan.getPeriod());
            }
            generated++;
        }
        // 待摊单：全部计划行已生成 → COMPLETED
        java.util.Set<String> deferredIds = new java.util.LinkedHashSet<>();
        for (AmortPlan plan : rows) {
            if (AmortPlan.TYPE_AMORT.equals(plan.getPlanType())) {
                deferredIds.add(plan.getDocId());
            }
        }
        for (String docId : deferredIds) {
            long notGen = planDao.selectList(new LambdaQueryWrapper<AmortPlan>()
                    .eq(AmortPlan::getDocId, docId)
                    .eq(AmortPlan::getGenStatus, AmortPlan.GEN_NOT)
                    .eq(AmortPlan::getDelFlag, "0")).size();
            if (notGen == 0) {
                deferredDao.update(null, new LambdaUpdateWrapper<DeferredDoc>()
                        .eq(DeferredDoc::getId, docId)
                        .set(DeferredDoc::getStatus, DeferredDoc.ST_COMPLETED)
                        .set(DeferredDoc::getUpdateBy, currentUser())
                        .set(DeferredDoc::getUpdateDate, LocalDateTime.now()));
            }
        }
        log.info("amort generate {}: generated={} skipped={} by {}", period, generated, skipped,
                currentUser());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("skipped", skipped);
        out.put("period", period);
        return out;
    }

    // ================================================================
    // 4.3 预提实际冲销与差异调整
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> settlePrepaid(String id, Map<String, Object> payload) {
        requireFinance("冲销预提费用");
        PrepaidDoc doc = prepaidDao.selectById(id);
        if (doc == null) {
            throw new ServiceException(404, "预提单不存在");
        }
        if (PrepaidDoc.ST_SETTLED.equals(doc.getStatus())) {
            throw new ServiceException(422, "预提单已冲销，禁止重复：" + doc.getPreNo());
        }
        BigDecimal actual = dec(payload.get("actualAmount"));
        if (actual == null || actual.signum() <= 0) {
            throw new ServiceException(422, "实际金额必须大于 0");
        }
        String mode = hasText(str(payload.get("settleMode"))) ? str(payload.get("settleMode")) : "PAYMENT";
        LocalDate settleDate;
        try {
            settleDate = LocalDate.parse(hasText(str(payload.get("payDate")))
                    ? str(payload.get("payDate")) : LocalDate.now().toString());
        } catch (RuntimeException e) {
            throw new ServiceException(422, "冲销日期格式须为 yyyy-MM-dd");
        }

        // 贷方：PAYMENT 走银行（余额条件扣减 BR-4.6-21）；ON_ACCOUNT 挂应付
        String creditAcct;
        String creditName;
        if ("PAYMENT".equals(mode)) {
            FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
            if (bank == null || !FinBankAccount.ST_ACTIVE.equals(bank.getStatus())) {
                throw new ServiceException(422, "银行账户必填且须启用");
            }
            if (bankDao.deductBalance(bank.getId(), actual) == 0) {
                throw new ServiceException(422, "银行账户余额不足：" + bank.getAccountName());
            }
            creditAcct = "1002";
            creditName = "银行存款-" + bank.getAccountName();
        } else if ("ON_ACCOUNT".equals(mode)) {
            creditAcct = "2202";
            creditName = "应付账款-预提转应付";
        } else {
            throw new ServiceException(422, "冲销方式仅支持 PAYMENT/ON_ACCOUNT");
        }

        // 差异：|实际 − 预提| / 预提（C-4.6-16）；差异行恒生成保借贷平衡，>0.5% 摘要标记差异率
        BigDecimal diff = actual.subtract(doc.getAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = doc.getAmount().signum() == 0 ? BigDecimal.ZERO
                : diff.abs().divide(doc.getAmount(), 4, RoundingMode.HALF_UP);
        boolean overTolerance = rate.compareTo(TOLERANCE) > 0;

        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of("2251", "DR", doc.getAmount(),
                "预提费用冲销-" + doc.getPreNo()));
        if (diff.signum() != 0) {
            String diffSummary = overTolerance
                    ? String.format("预提差异调整（差异率 %.2f%%，C-4.6-16）",
                        rate.multiply(new BigDecimal("100")).doubleValue())
                    : "预提实际差异（容差内）";
            lines.add(GlVoucherService.FinVoucherLineSpec.of(
                    diff.signum() > 0 ? doc.getAccountCode() : doc.getAccountCode(),
                    diff.signum() > 0 ? "DR" : "CR", diff.abs(), diffSummary));
        }
        lines.add(GlVoucherService.FinVoucherLineSpec.of(creditAcct, "CR", actual, creditName));

        var v = voucherService.create("PSETTLE", settleDate,
                "预提实际冲销：" + doc.getPreNo() + (overTolerance
                        ? String.format("（差异率 %.2f%% 超容差自动调整）",
                            rate.multiply(new BigDecimal("100")).doubleValue()) : ""),
                BIZ_PREPAY, doc.getPreNo(), null, lines);
        if (overTolerance) {
            log.info("[C-4.6-16] prepaid {} variance rate {}% > 0.5%, adjustment line booked",
                    doc.getPreNo(), rate.multiply(new BigDecimal("100")));
        }

        doc.setStatus(PrepaidDoc.ST_SETTLED);
        doc.setActualAmount(actual);
        doc.setSettleDate(settleDate);
        doc.setSettleVoucherId(v.getId());
        doc.setSettleRemark(str(payload.get("remark")));
        doc.setUpdateBy(currentUser());
        doc.setUpdateDate(LocalDateTime.now());
        prepaidDao.updateById(doc);

        log.info("prepaid {} settled: prepaid={} actual={} diff={} mode={} by {}",
                doc.getPreNo(), doc.getAmount(), actual, diff, mode, currentUser());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("preNo", doc.getPreNo());
        out.put("voucherNo", v.getVoucherNo());
        out.put("difference", diff);
        out.put("varianceRate", rate);
        out.put("overTolerance", overTolerance);
        return out;
    }

    // ================================================================
    // 4.4 年末余额检查（L4 黄标）
    // ================================================================

    @Override
    public Map<String, Object> yearEndCheck(String period) {
        List<Map<String, Object>> items = new ArrayList<>();
        // 预提：年末余额须清零或转出（BR-4.6-06）——负债方向累计取绝对值
        BigDecimal prepaidCum = nvl(balanceDao.cumulativeBalance("2251", period));
        boolean prepaidOk = prepaidCum.compareTo(BigDecimal.ZERO) == 0;
        Map<String, Object> it1 = new LinkedHashMap<>();
        it1.put("key", "prepaidYearEnd");
        it1.put("name", "预提费用年末余额清零（BR-4.6-06）");
        it1.put("ok", prepaidOk);
        it1.put("level", "L4");
        it1.put("detail", prepaidOk ? "预提科目余额已清零"
                : "预提科目累计余额 " + prepaidCum.abs().toPlainString() + " ≠ 0，须清零或转入其他应付款/应付账款");
        items.add(it1);
        // 待摊：科目余额 vs Σ未摊销计划剩余
        BigDecimal deferredCum = nvl(balanceDao.cumulativeBalance("1801", period)).abs();
        BigDecimal remaining = nvl(planDao.remainingAmortPlan());
        boolean deferredOk = deferredCum.compareTo(remaining) == 0;
        Map<String, Object> it2 = new LinkedHashMap<>();
        it2.put("key", "deferredRecon");
        it2.put("name", "待摊费用余额 = 未摊销计划剩余（FR-4.6-8-6）");
        it2.put("ok", deferredOk);
        it2.put("level", "L4");
        it2.put("detail", deferredOk ? "待摊余额与未摊销计划勾稽一致"
                : "待摊科目余额 " + deferredCum.toPlainString() + " vs 未摊销计划剩余 "
                        + remaining.toPlainString() + "，差额 "
                        + deferredCum.subtract(remaining).abs().toPlainString() + " 须排查");
        items.add(it2);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("items", items);
        out.put("allOk", items.stream().allMatch(m -> Boolean.TRUE.equals(m.get("ok"))));
        return out;
    }

    @Override
    public long notGeneratedCount(String period) {
        return planDao.countNotGenerated(period);
    }

    // ================================================================
    // 列表与工具
    // ================================================================

    @Override
    public List<Map<String, Object>> listDocs(String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!AmortPlan.TYPE_AMORT.equals(type)) {
            for (PrepaidDoc d : prepaidDao.selectList(new LambdaQueryWrapper<PrepaidDoc>()
                    .orderByDesc(PrepaidDoc::getPeriod))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("bizType", "PREPAY");
                m.put("docNo", d.getPreNo());
                m.put("accountCode", d.getAccountCode());
                m.put("accountName", d.getAccountName());
                m.put("period", d.getPeriod());
                m.put("amount", d.getAmount());
                m.put("status", d.getStatus());
                m.put("actualAmount", d.getActualAmount());
                m.put("settleDate", d.getSettleDate());
                m.put("remark", d.getRemark());
                m.put("notGenerated", planDao.countNotGenerated(d.getPeriod()));
                out.add(m);
            }
        }
        if (!AmortPlan.TYPE_PREPAY.equals(type)) {
            for (DeferredDoc d : deferredDao.selectList(new LambdaQueryWrapper<DeferredDoc>()
                    .orderByDesc(DeferredDoc::getStartPeriod))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("bizType", "AMORT");
                m.put("docNo", d.getDefNo());
                m.put("summary", d.getSummary());
                m.put("expenseAccountCode", d.getExpenseAccountCode());
                m.put("totalAmount", d.getTotalAmount());
                m.put("amortMonths", d.getAmortMonths());
                m.put("startPeriod", d.getStartPeriod());
                m.put("assetAccountCode", d.getAssetAccountCode());
                m.put("status", d.getStatus());
                long notGen = planDao.selectCount(new LambdaQueryWrapper<AmortPlan>()
                        .eq(AmortPlan::getDocId, d.getId())
                        .eq(AmortPlan::getGenStatus, AmortPlan.GEN_NOT));
                m.put("notGenerated", notGen);
                m.put("generatedCount", d.getAmortMonths() - notGen);
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> docDetail(String id) {
        List<AmortPlan> plans = planDao.selectList(new LambdaQueryWrapper<AmortPlan>()
                .eq(AmortPlan::getDocId, id)
                .orderByAsc(AmortPlan::getPeriod));
        if (plans.isEmpty()) {
            throw new ServiceException(404, "单据不存在或无计划行");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", id);
        out.put("docNo", plans.get(0).getDocNo());
        out.put("planType", plans.get(0).getPlanType());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AmortPlan p : plans) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", p.getPeriod());
            m.put("amount", p.getAmount());
            m.put("genStatus", p.getGenStatus());
            m.put("voucherId", p.getVoucherId());
            rows.add(m);
        }
        out.put("plans", rows);
        return out;
    }

    private void insertPlan(String type, String docId, String docNo, String period, BigDecimal amount) {
        AmortPlan p = new AmortPlan();
        p.setId(uuid());
        p.setPlanType(type);
        p.setDocId(docId);
        p.setDocNo(docNo);
        p.setPeriod(period);
        p.setAmount(amount);
        p.setGenStatus(AmortPlan.GEN_NOT);
        p.setCreateBy(currentUser());
        p.setCreateDate(LocalDateTime.now());
        p.setVerNo(0);
        planDao.insert(p);
    }

    /** L1：计划合计 = 登记总额（FR-4.6-8-2 不平阻断） */
    private void assertPlanTotal(String docId, BigDecimal expected) {
        BigDecimal sum = nvl(planDao.sumByDoc(docId));
        if (sum.compareTo(expected) != 0) {
            throw new ServiceException(422, "计划金额与登记总额不平（L1 硬阻断）：计划 "
                    + sum.toPlainString() + " vs 登记 " + expected.toPlainString());
        }
    }

    private FinAccount requireExpenseAccount(String accountCode) {
        if (!hasText(accountCode)) {
            throw new ServiceException(422, "费用科目必填");
        }
        FinAccount acct = accountDao.selectOne(new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getAccountCode, accountCode)
                .last("LIMIT 1"));
        if (acct == null) {
            throw new ServiceException(422, "费用科目不存在：" + accountCode);
        }
        if (!"DR".equals(acct.getDirection())) {
            throw new ServiceException(422, "费用科目须为借方（DR）方向：" + accountCode);
        }
        return acct;
    }

    private static LocalDate lastDayOf(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        LocalDate d = LocalDate.of(y, m, 1);
        return d.withDayOfMonth(d.lengthOfMonth());
    }

    private String nextNo(String prefix, java.util.function.Function<String, Integer> maxFn) {
        String p = prefix + LocalDate.now().format(DAY);
        Integer max = maxFn.apply(p); // MAX 无记录返回 NULL，防拆箱 NPE
        return p + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private Map<String, Object> docRow(PrepaidDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("preNo", d.getPreNo());
        m.put("period", d.getPeriod());
        m.put("amount", d.getAmount());
        m.put("accountCode", d.getAccountCode());
        m.put("status", d.getStatus());
        return m;
    }

    private Map<String, Object> docRow(DeferredDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("defNo", d.getDefNo());
        m.put("totalAmount", d.getTotalAmount());
        m.put("amortMonths", d.getAmortMonths());
        m.put("startPeriod", d.getStartPeriod());
        m.put("status", d.getStatus());
        return m;
    }

    private void requireFinance(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r) || "ROLE_FIN_MGR".equalsIgnoreCase(r)
                    || "ROLE_FIN_ASSET".equalsIgnoreCase(r) || "ROLE_FINANCE_MGR".equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || str(o).isBlank()) {
            return null;
        }
        return new BigDecimal(str(o));
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}

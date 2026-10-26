package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.util.SecurityUtils;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FxRevalDao;
import com.erp.dao.mdm.MdmExchangeRateDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FxReval;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.service.fin.FxRevalService;
import com.erp.service.fin.GlVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 外币期末重估实现（add-fx-revaluation，spec fx-revaluation）。
 * 试算（design D6 不落库）与生成（D4/D7：上期红冲一体 + 台账 + DRAFT 凭证）共享同一计算内核。
 * 汇兑差额 = 外币额 ×（期末中间价 − 原记账汇率），汇率 6 位、金额 2 位 HALF_UP（IAS 21）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRevalServiceImpl implements FxRevalService {

    /** 汇兑损益科目（146 种子；6603 已被租赁利息占用，决策③） */
    private static final String ACCT_FXGainLoss = "6604";
    private static final String ACCT_AR = "1122";
    private static final String ACCT_AP = "2202";
    private static final String ACCT_BANK = "1002";
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final FinApInvoiceDao apDao;
    private final ArInvoiceDao arDao;
    private final FinBankAccountDao bankDao;
    private final FxRevalDao revalDao;
    private final MdmExchangeRateDao rateDao;
    private final GlVoucherService voucherService;

    // ================================================================
    // 试算（不落库）
    // ================================================================

    @Override
    public Map<String, Object> trialRun(String period) {
        LocalDate periodEnd = periodEnd(period);
        List<Map<String, Object>> items = scanOpenItems(periodEnd);
        assertRates(periodEnd, items);

        List<Map<String, Object>> details = new ArrayList<>();
        BigDecimal totalDiff = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map<String, Object> it : items) {
            BigDecimal diff = diffOf(it);
            it.put("diffAmount", diff);
            totalDiff = totalDiff.add(diff);
            details.add(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("periodEnd", periodEnd.toString());
        out.put("items", details);
        out.put("summary", summarize(details));
        out.put("totalDiff", totalDiff);
        return out;
    }

    // ================================================================
    // 生成（上期冲回一体 + 台账 + DRAFT 凭证）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> generate(String period) {
        LocalDate periodEnd = periodEnd(period);
        // 同期重复生成 422（台账已有该期间记录）
        if (revalDao.selectCountByPeriod(period) > 0) {
            throw new ServiceException(422, "本期重估已生成（期间 " + period + "），不可重复生成");
        }
        // 上期 DRAFT/AUDITED 重估凭证作废（无可冲回，直接清理旧草稿）
        int voided = voidPriorDrafts(period);
        // 上期 POSTED 重估凭证自动红冲（BR-4.6-25 一体原子操作）
        Map<String, Object> reversed = reversePriorPosted(period);

        // 重跑同一计算内核
        List<Map<String, Object>> items = scanOpenItems(periodEnd);
        assertRates(periodEnd, items);
        if (items.isEmpty()) {
            throw new ServiceException(422, "本期无外币货币性项目敞口（AP 未清/外币账户/AR 未清均为空），无需重估");
        }

        String revalNo = nextRevalNo(period);
        String batchAnchor = "REVAL-" + period;
        String voucherId = null;
        String voucherNo = null;
        BigDecimal totalDiff = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        List<FxReval> rows = new ArrayList<>();
        for (Map<String, Object> it : items) {
            BigDecimal diff = diffOf(it);
            totalDiff = totalDiff.add(diff);
            String itemType = (String) it.get("itemType");
            String itemAcct = FxReval.TYPE_AR.equals(itemType) ? ACCT_AR
                    : FxReval.TYPE_AP.equals(itemType) ? ACCT_AP : ACCT_BANK;
            String party = (String) it.get("partyName");
            BigDecimal abs = diff.abs();
            if (diff.signum() > 0) {
                // 汇兑收益：借 货币性项目 / 贷 6604
                lines.add(GlVoucherService.FinVoucherLineSpec.of(itemAcct, "DR", abs,
                        "汇兑收益-" + party + "（" + it.get("itemNo") + "）"));
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_FXGainLoss, "CR", abs,
                        "汇兑损益-" + itemType + " " + it.get("currency")));
            } else if (diff.signum() < 0) {
                // 汇兑损失：借 6604 / 贷 货币性项目
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_FXGainLoss, "DR", abs,
                        "汇兑损失-" + itemType + " " + it.get("currency")));
                lines.add(GlVoucherService.FinVoucherLineSpec.of(itemAcct, "CR", abs,
                        "汇兑损失-" + party + "（" + it.get("itemNo") + "）"));
            }

            FxReval row = new FxReval();
            row.setId(uuid());
            row.setRevalNo(revalNo);
            row.setPeriod(period);
            row.setItemType(itemType);
            row.setItemId((String) it.get("itemId"));
            row.setItemNo((String) it.get("itemNo"));
            row.setPartyId((String) it.get("partyId"));
            row.setCurrency((String) it.get("currency"));
            row.setFcAmount((BigDecimal) it.get("fcAmount"));
            row.setBookingRate((BigDecimal) it.get("bookingRate"));
            row.setPeriodRate((BigDecimal) it.get("periodRate"));
            row.setDiffAmount(diff);
            row.setCreateBy(currentUser());
            rows.add(row);
        }

        // DRAFT 重估凭证（decision④：财务主管 AUDITED 后 POSTED；制单≠审核 C-4.6-07）
        FinVoucher v = voucherService.create("REVAL", periodEnd,
                "外币期末重估 " + period + (totalDiff.signum() >= 0 ? "（净收益）" : "（净损失）"),
                "REVAL", batchAnchor, null, lines, true);
        voucherId = v.getId();
        voucherNo = v.getVoucherNo();
        for (FxReval row : rows) {
            row.setVoucherId(voucherId);
            revalDao.insert(row);
        }

        log.info("fx reval generated: {} items={} totalDiff={} voucher={} voided={} reversed={}",
                revalNo, rows.size(), totalDiff, voucherNo, voided, reversed.get("count"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revalNo", revalNo);
        out.put("period", period);
        out.put("itemCount", rows.size());
        out.put("totalDiff", totalDiff);
        out.put("voucherId", voucherId);
        out.put("voucherNo", voucherNo);
        out.put("voidedPriorDrafts", voided);
        out.put("reversedPrior", reversed);
        return out;
    }

    // ================================================================
    // 共享内核：扫描三类外币货币性项目 + 期末汇率校验 + 差额计算
    // ================================================================

    /** 扫描外币货币性项目（AP 未清 / 外币账户 FC_BALANCE≠0 / AR 未清），每项含记账汇率与期末汇率 */
    private List<Map<String, Object>> scanOpenItems(LocalDate periodEnd) {
        List<Map<String, Object>> items = new ArrayList<>();
        // AP 未清（POSTED，未清 = TOTAL − PAID > 0）
        for (FinApInvoice inv : apDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                .ne(FinApInvoice::getCurrency, "CNY"))) {
            BigDecimal open = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
            if (open.signum() <= 0) {
                continue;
            }
            items.add(item(FxReval.TYPE_AP, inv.getId(), inv.getInvoiceNo(), inv.getSupplierId(),
                    inv.getSupplierName(), inv.getCurrency(), open, nvl(inv.getBookingRate()), periodEnd));
        }
        // AR 未清（未清 = AMOUNT − RED − PAID > 0）
        for (ArInvoice ar : arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .ne(ArInvoice::getCurrency, "CNY"))) {
            BigDecimal open = nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                    .subtract(nvl(ar.getPaidAmount()));
            if (open.signum() <= 0) {
                continue;
            }
            items.add(item(FxReval.TYPE_AR, ar.getId(), ar.getArNo(), ar.getCustomerId(),
                    ar.getCustomerName(), ar.getCurrency(), open, nvl(ar.getBookingRate()), periodEnd));
        }
        // 外币银行账户（FC_BALANCE ≠ 0）
        for (FinBankAccount acc : bankDao.selectList(new LambdaQueryWrapper<FinBankAccount>()
                .eq(FinBankAccount::getStatus, FinBankAccount.ST_ACTIVE)
                .ne(FinBankAccount::getCurrency, "CNY"))) {
            BigDecimal fc = nvl(acc.getFcBalance());
            if (fc.signum() == 0) {
                continue;
            }
            items.add(item(FxReval.TYPE_BANK, acc.getId(), acc.getAccountNo(), null,
                    acc.getAccountName(), acc.getCurrency(), fc, nvl(acc.getBookingRate()), periodEnd));
        }
        return items;
    }

    private Map<String, Object> item(String type, String id, String no, String partyId,
                                     String partyName, String currency, BigDecimal fcAmount,
                                     BigDecimal bookingRate, LocalDate periodEnd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemType", type);
        m.put("itemId", id);
        m.put("itemNo", no);
        m.put("partyId", partyId);
        m.put("partyName", partyName);
        m.put("currency", currency);
        m.put("fcAmount", fcAmount);
        m.put("bookingRate", bookingRate);
        MdmExchangeRate hit = rateDao.selectHit(currency, "CNY", "MIDDLE", periodEnd);
        m.put("periodRate", hit != null ? hit.getRate() : null);
        return m;
    }

    /** 期末汇率缺失 → 逐币种列示阻断（C-4.6-03 L1） */
    private void assertRates(LocalDate periodEnd, List<Map<String, Object>> items) {
        TreeSet<String> missing = new TreeSet<>();
        for (Map<String, Object> it : items) {
            if (it.get("periodRate") == null) {
                missing.add((String) it.get("currency"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "期末汇率缺失（C-4.6-03 L1 阻断）：以下币种无 "
                    + periodEnd + " 生效的中间价——" + String.join("、", missing)
                    + "，请先维护汇率主数据");
        }
    }

    /** 汇兑差额 = 外币额 ×（期末汇率 − 记账汇率），2 位 HALF_UP（IAS 21，6 位汇率） */
    private BigDecimal diffOf(Map<String, Object> it) {
        BigDecimal fc = (BigDecimal) it.get("fcAmount");
        BigDecimal booking = (BigDecimal) it.get("bookingRate");
        BigDecimal periodRate = (BigDecimal) it.get("periodRate");
        return fc.multiply(periodRate.subtract(booking)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 币种 × 项目类型汇总 */
    private List<Map<String, Object>> summarize(List<Map<String, Object>> details) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> d : details) {
            String key = d.get("currency") + "|" + d.get("itemType");
            Map<String, Object> g = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("currency", d.get("currency"));
                m.put("itemType", d.get("itemType"));
                m.put("itemCount", 0);
                m.put("fcTotal", BigDecimal.ZERO);
                m.put("diffTotal", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                return m;
            });
            g.put("itemCount", (Integer) g.get("itemCount") + 1);
            g.put("fcTotal", ((BigDecimal) g.get("fcTotal")).add((BigDecimal) d.get("fcAmount")));
            g.put("diffTotal", ((BigDecimal) g.get("diffTotal")).add((BigDecimal) d.get("diffAmount")));
        }
        return new ArrayList<>(byKey.values());
    }

    // ================================================================
    // 上期冲回（BR-4.6-25）
    // ================================================================

    /** 上期 POSTED 重估凭证自动红冲（REVERSES 链），返回冲回摘要 */
    private Map<String, Object> reversePriorPosted(String period) {
        String prior = priorPeriod(period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", prior);
        List<String> reversedVouchers = new ArrayList<>();
        for (Map<String, Object> v : postedRevalVouchers(prior)) {
            String vid = (String) v.get("id");
            FinVoucher rev = voucherService.reverse(vid, "上期重估自动冲回（BR-4.6-25）",
                    "REVAL-RV-" + period);
            reversedVouchers.add(rev.getVoucherNo());
            // 台账标记被冲回
            for (FxReval row : revalDao.selectList(new LambdaQueryWrapper<FxReval>()
                    .eq(FxReval::getPeriod, prior)
                    .eq(FxReval::getVoucherId, vid))) {
                row.setReversedBy(rev.getId());
                row.setUpdateBy(currentUser());
                revalDao.updateById(row);
            }
        }
        out.put("count", reversedVouchers.size());
        out.put("vouchers", reversedVouchers);
        return out;
    }

    /** 上期 DRAFT/AUDITED 重估凭证作废（未过账无需红冲，直接清理） */
    private int voidPriorDrafts(String period) {
        String prior = priorPeriod(period);
        int n = 0;
        for (Map<String, Object> v : voucherService.listBySource("REVAL", "REVAL-" + prior)) {
            String st = String.valueOf(v.get("status"));
            if (!"POSTED".equals(st)) {
                voucherService.voidDraft((String) v.get("id"));
                n++;
            }
        }
        return n;
    }

    /** 某期间已过账重估凭证（批次锚 REVAL-YYYYMM；listBySource 返回 Map 形态） */
    private List<Map<String, Object>> postedRevalVouchers(String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : voucherService.listBySource("REVAL", "REVAL-" + period)) {
            if ("POSTED".equals(String.valueOf(m.get("status")))) {
                out.add(m);
            }
        }
        return out;
    }

    // ================================================================
    // 批次列表与下钻（task 4.4）
    // ================================================================

    @Override
    public List<Map<String, Object>> batches() {
        // 全部期间（倒序）——台账有记录才算生成过批次
        List<String> periods = revalDao.selectList(new LambdaQueryWrapper<FxReval>()
                        .select(FxReval::getPeriod)
                        .groupBy(FxReval::getPeriod)
                        .orderByDesc(FxReval::getPeriod))
                .stream().map(FxReval::getPeriod).distinct().toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : periods) {
            List<FxReval> rows = revalDao.selectByPeriod(p);
            BigDecimal revalTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            String voucherId = null;
            for (FxReval r : rows) {
                revalTotal = revalTotal.add(nvl(r.getDiffAmount()));
                if (voucherId == null) {
                    voucherId = r.getVoucherId();
                }
            }
            // 本期冲回额 = 本期生成时被红冲的上期台账合计（REVERSED_BY ∈ 本期红冲凭证集 REVAL-RV-P）
            BigDecimal reversedTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            int reversedCount = 0;
            java.util.Set<String> rvIds = reversalVoucherIds(p);
            if (!rvIds.isEmpty()) {
                for (FxReval r : revalDao.selectList(new LambdaQueryWrapper<FxReval>()
                        .ne(FxReval::getPeriod, p))) {
                    if (has(r.getReversedBy()) && rvIds.contains(r.getReversedBy())) {
                        reversedTotal = reversedTotal.add(nvl(r.getDiffAmount()));
                        reversedCount++;
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", p);
            m.put("itemCount", rows.size());
            m.put("revalTotal", revalTotal);
            m.put("reversedTotal", reversedTotal);
            m.put("reversedCount", reversedCount);
            m.put("netTotal", revalTotal.subtract(reversedTotal)); // 冲回额为被冲上期差额（带符号），净额=本期−冲回
            m.put("voucherId", voucherId);
            m.put("voucherNo", voucherNoOf(voucherId));
            m.put("voucherStatus", voucherStatusOf(voucherId));
            out.add(m);
        }
        return out;
    }

    /** 本期生成时打出的红冲凭证 ID 集（sourceDocNo = REVAL-RV-期间） */
    private java.util.Set<String> reversalVoucherIds(String period) {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (Map<String, Object> m : voucherService.listBySource("REVAL", "REVAL-RV-" + period)) {
            if (m.get("id") != null) {
                ids.add(String.valueOf(m.get("id")));
            }
        }
        return ids;
    }

    @Override
    public Map<String, Object> batchDetails(String period) {
        periodEnd(period); // 期间格式校验
        List<FxReval> rows = revalDao.selectByPeriod(period);
        List<Map<String, Object>> details = new ArrayList<>();
        for (FxReval r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revalNo", r.getRevalNo());
            m.put("itemType", r.getItemType());
            m.put("itemId", r.getItemId());
            m.put("itemNo", r.getItemNo());
            m.put("partyId", r.getPartyId());
            m.put("currency", r.getCurrency());
            m.put("fcAmount", r.getFcAmount());
            m.put("bookingRate", r.getBookingRate());
            m.put("periodRate", r.getPeriodRate());
            m.put("diffAmount", r.getDiffAmount());
            m.put("reversedBy", r.getReversedBy());
            m.put("createDate", r.getCreateDate());
            details.add(m);
        }
        // 币种×类型汇总（复用 summarize 输入形态）
        List<Map<String, Object>> summary = summarize(details);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("summary", summary);
        out.put("items", details);
        // 凭证链：本批重估凭证 + 本期生成时打出的冲回凭证（REVAL-RV-P 锚点，非上期行反查）
        List<Map<String, Object>> vouchers = new ArrayList<>();
        java.util.TreeSet<String> seen = new java.util.TreeSet<>();
        for (FxReval r : rows) {
            if (has(r.getVoucherId()) && seen.add(r.getVoucherId())) {
                vouchers.add(voucherInfo(r.getVoucherId(), "本批重估凭证"));
            }
        }
        for (String rvId : reversalVoucherIds(period)) {
            if (seen.add(rvId)) {
                vouchers.add(voucherInfo(rvId, "冲回凭证"));
            }
        }
        out.put("vouchers", vouchers);
        return out;
    }

    private Map<String, Object> voucherInfo(String voucherId, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("voucherId", voucherId);
        try {
            Map<String, Object> d = voucherService.detail(voucherId);
            m.put("voucherNo", d.get("voucherNo"));
            m.put("status", d.get("status"));
            m.put("voucherDate", d.get("voucherDate"));
        } catch (RuntimeException e) {
            m.put("voucherNo", null);
            m.put("status", "DELETED");
        }
        return m;
    }

    private String voucherNoOf(String voucherId) {
        if (!has(voucherId)) {
            return null;
        }
        try {
            return (String) voucherService.detail(voucherId).get("voucherNo");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String voucherStatusOf(String voucherId) {
        if (!has(voucherId)) {
            return null;
        }
        try {
            return String.valueOf(voucherService.detail(voucherId).get("status"));
        } catch (RuntimeException e) {
            return "DELETED";
        }
    }

    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    // ================================================================
    // 工具
    // ================================================================

    private LocalDate periodEnd(String period) {
        try {
            return YearMonth.parse(period, YM).atEndOfMonth();
        } catch (Exception e) {
            throw new ServiceException(422, "期间格式须为 yyyyMM：" + period);
        }
    }

    private String priorPeriod(String period) {
        return YearMonth.parse(period, YM).minusMonths(1).format(YM);
    }

    private String nextRevalNo(String period) {
        Integer max = revalDao.selectMaxSeq(period);
        return "REVAL-" + period + "-" + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

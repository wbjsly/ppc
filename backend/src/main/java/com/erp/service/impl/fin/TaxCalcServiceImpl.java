package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.dao.fin.TaxCalcLedgerDao;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.entity.fin.FinBalance;
import com.erp.entity.fin.TaxCalcLedger;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.SysParamService;
import com.erp.service.fin.TaxCalcService;
import com.erp.service.mdm.MdmTaxCodeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计税引擎实现（M9-1，spec tax-calculation）。
 * D1：匹配复用 MdmTaxCodeService.trial；D2：计算纯函数化；
 * D3：台账状态机 RECORDED→APPLYING→VOUCHED；D4：税额挂账分录（借贷平衡）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxCalcServiceImpl implements TaxCalcService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final MdmTaxCodeDao taxCodeDao;
    private final MdmTaxCodeService taxCodeService;
    private final TaxCalcLedgerDao ledgerDao;
    private final FinBalanceDao balanceDao;
    private final GlVoucherService voucherService;
    private final SysParamService sysParamService;

    // ---------- 2.1 税码匹配（FR-4.14-1-2 / C-4.14-01 / BR-4.14-10） ----------

    @Override
    public Map<String, Object> match(String trxType, String scope, String calcType,
                                     String rateKind, LocalDate businessDate) {
        if (!"SALES".equals(trxType) && !"PURCHASE".equals(trxType)) {
            throw new ServiceException(422, "交易类型仅支持 SALES/PURCHASE");
        }
        if (!hasText(scope) || !hasText(calcType)) {
            throw new ServiceException(422, "适用范围与计税方式必填");
        }
        LocalDate d = businessDate == null ? LocalDate.now() : businessDate;

        // 按 (SCOPE, CALC_TYPE[, RATE_KIND]) 枚举候选税码，逐个按业务发生日 trial 收集命中集（D1）
        Set<String> candidates = new LinkedHashSet<>();
        LambdaQueryWrapper<MdmTaxCode> cq = new LambdaQueryWrapper<MdmTaxCode>()
                .eq(MdmTaxCode::getScope, scope.trim().toUpperCase())
                .eq(MdmTaxCode::getCalcType, calcType.trim().toUpperCase());
        if (hasText(rateKind)) { // STANDARD/LOW/ZERO——现有主数据维度（决策②）
            cq.eq(MdmTaxCode::getRateKind, rateKind.trim().toUpperCase());
        }
        for (MdmTaxCode row : taxCodeDao.selectList(cq)) {
            candidates.add(row.getTaxCode());
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> missReasons = new ArrayList<>();
        for (String code : candidates) {
            Map<String, Object> t = taxCodeService.trial(code, d);
            if (Boolean.TRUE.equals(t.get("applicable"))) {
                hits.add(t);
            } else {
                @SuppressWarnings("unchecked")
                List<String> rs = (List<String>) t.get("reasons");
                if (rs != null) {
                    missReasons.addAll(rs);
                }
            }
        }
        if (hits.isEmpty()) {
            // C-4.14-01 L1 硬阻断：税率主数据缺失或已过期
            throw new ServiceException(422, "税率主数据缺失或已过期（C-4.14-01）："
                    + (missReasons.isEmpty() ? "该范围/计税方式无任何税码记录" : String.join("；", missReasons))
                    + "——须主数据管理员维护生效后方可继续");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", d.toString());
        out.put("trxType", trxType);
        out.put("scope", scope.toUpperCase());
        out.put("calcType", calcType.toUpperCase());
        if (hasText(rateKind)) {
            out.put("rateKind", rateKind.toUpperCase());
        }
        out.put("candidateCount", hits.size());
        out.put("multiCandidate", hits.size() > 1); // L4 人工选择
        out.put("candidates", hits);

        // BR-4.14-10 税率适用时点：按业务发生日取码；与今日口径不一致时标记备注要素
        if (!d.equals(LocalDate.now())) {
            for (MdmTaxCode row : taxCodeDao.selectList(new LambdaQueryWrapper<MdmTaxCode>()
                    .in(MdmTaxCode::getTaxCode, hits.stream().map(h -> (String) h.get("taxCode")).toList()))) {
                Map<String, Object> today = taxCodeService.trial(row.getTaxCode(), LocalDate.now());
                Object todayRate = today.get("applicable") == Boolean.TRUE ? today.get("taxRate") : null;
                if (todayRate == null || new BigDecimal(String.valueOf(todayRate))
                        .compareTo(new BigDecimal(String.valueOf(row.getTaxRate()))) != 0) {
                    out.put("rateNote", "按业务发生日税率 " + row.getTaxRate().multiply(BigDecimal.valueOf(100))
                            .setScale(0, RoundingMode.HALF_UP) + "% 适用（BR-4.14-10，开票备注要素）");
                    break;
                }
            }
        }
        return out;
    }

    // ---------- 2.2 税额计算（FR-4.14-1-3/4 / BR-4.14-07/08/12） ----------

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> calc(Map<String, Object> payload) {
        String calcType = str(payload.get("calcType"));
        if (!hasText(calcType)) {
            throw new ServiceException(422, "计税方式必填（GENERAL/SIMPLE）");
        }
        calcType = calcType.trim().toUpperCase();
        boolean simple = "SIMPLE".equals(calcType);
        if (!simple && !"GENERAL".equals(calcType)) {
            throw new ServiceException(422, "计税方式仅支持 GENERAL/SIMPLE");
        }
        boolean gross = !"N".equals(str(payload.get("grossFlag"))); // 默认含税 Y
        BigDecimal discount = dec(payload.get("discount"), BigDecimal.ZERO); // 整单折扣（不含税口径抵减）
        BigDecimal freight = dec(payload.get("freight"), BigDecimal.ZERO);   // 运费（加入基数）

        List<Map<String, Object>> inLines = (List<Map<String, Object>>) payload.get("lines");
        if (inLines == null || inLines.isEmpty()) {
            throw new ServiceException(422, "至少一个行项目");
        }

        // 逐行基数（含税先价税分离 BR-4.14-07；SIMPLE 征收率同式分离）
        List<BigDecimal> bases = new ArrayList<>();
        List<BigDecimal> rates = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalBase = BigDecimal.ZERO;
        for (Map<String, Object> ln : inLines) {
            BigDecimal amt = dec(ln.get("amount"), null);
            if (amt == null || amt.signum() <= 0) {
                throw new ServiceException(422, "行金额须为正数");
            }
            BigDecimal rate = dec(ln.get("taxRate"), dec(payload.get("taxRate"), null));
            if (rate == null) {
                throw new ServiceException(422, "行税率缺失（传行级 taxRate 或整体 taxRate）");
            }
            BigDecimal base = gross
                    ? amt.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP)
                    : amt.setScale(2, RoundingMode.HALF_UP);
            bases.add(base);
            rates.add(rate);
            totalGross = totalGross.add(amt);
            totalBase = totalBase.add(base);
        }
        if (totalBase.signum() <= 0) {
            throw new ServiceException(422, "计税基数为零");
        }

        // 折扣/运费按各行不含税占比分摊，末行吃尾差保合计一致（BR-4.14-12 / D2）
        int n = bases.size();
        BigDecimal allocDiscount = BigDecimal.ZERO;
        BigDecimal allocFreight = BigDecimal.ZERO;
        List<BigDecimal> adjBases = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal ratio = bases.get(i).divide(totalBase, 8, RoundingMode.HALF_UP);
            BigDecimal dPart;
            BigDecimal fPart;
            if (i == n - 1) {
                dPart = discount.subtract(allocDiscount);
                fPart = freight.subtract(allocFreight);
            } else {
                dPart = discount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                fPart = freight.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                allocDiscount = allocDiscount.add(dPart);
                allocFreight = allocFreight.add(fPart);
            }
            adjBases.add(bases.get(i).subtract(dPart).add(fPart));
        }

        // 逐行计税
        List<Map<String, Object>> outLines = new ArrayList<>();
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalTaxable = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal adj = adjBases.get(i);
            if (adj.signum() < 0) {
                throw new ServiceException(422, "折扣分摊后第 " + (i + 1) + " 行基数为负，检查折扣金额");
            }
            BigDecimal tax = adj.multiply(rates.get(i)).setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> ol = new LinkedHashMap<>();
            ol.put("lineNo", i + 1);
            ol.put("taxableBase", adj);
            ol.put("taxRate", rates.get(i));
            ol.put("taxAmount", tax);
            outLines.add(ol);
            totalTax = totalTax.add(tax);
            totalTaxable = totalTaxable.add(adj);
        }

        // 尾差校验（BR-4.14-07 L4 不阻断）：含税且单一税率时按价税合计反算
        boolean overTolerance = false;
        BigDecimal tolerance = sysParamService.getRate("TOLERANCE_DEFAULT", new BigDecimal("0.005"));
        BigDecimal diff = BigDecimal.ZERO;
        Set<BigDecimal> uniqRates = new LinkedHashSet<>(rates);
        if (gross && uniqRates.size() == 1 && discount.signum() == 0 && freight.signum() == 0) {
            BigDecimal rate = rates.get(0);
            BigDecimal reverse = totalGross.subtract(
                    totalGross.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP));
            diff = totalTax.subtract(reverse).abs();
            overTolerance = diff.compareTo(tolerance) > 0;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calcType", calcType);
        out.put("grossFlag", gross ? "Y" : "N");
        out.put("lines", outLines);
        out.put("taxableBase", totalTaxable);
        out.put("taxAmount", totalTax);
        out.put("deductible", simple ? "N" : "Y"); // BR-4.14-08 简易不得抵扣
        out.put("overTolerance", overTolerance);
        out.put("toleranceDiff", diff);
        if (simple) {
            out.put("note", "简易计税：按征收率计税且不得抵扣进项（BR-4.14-08）");
        }
        return out;
    }

    // ---------- 3.1 台账 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ledger(Map<String, Object> payload) {
        String taxCode = str(payload.get("taxCode"));
        BigDecimal taxRate = dec(payload.get("taxRate"), null);
        BigDecimal taxableBase = dec(payload.get("taxableBase"), null);
        BigDecimal taxAmount = dec(payload.get("taxAmount"), null);
        BigDecimal amount = dec(payload.get("amount"), null);
        String trxType = str(payload.get("trxType"));
        String calcType = str(payload.get("calcType"));
        LocalDate businessDate = parseDate(str(payload.get("businessDate")));
        if (!hasText(taxCode) || taxRate == null || taxableBase == null || taxAmount == null
                || amount == null || !hasText(trxType) || !hasText(calcType) || businessDate == null) {
            throw new ServiceException(422, "台账落库要素缺失（taxCode/taxRate/taxableBase/taxAmount/amount/trxType/calcType/businessDate）");
        }
        if (!"SALES".equals(trxType.toUpperCase()) && !"PURCHASE".equals(trxType.toUpperCase())) {
            throw new ServiceException(422, "非法交易类型");
        }
        TaxCalcLedger l = new TaxCalcLedger();
        l.setTrxType(trxType.toUpperCase());
        l.setScope(str(payload.get("scope")));
        l.setCalcType(calcType.toUpperCase());
        l.setRateKind(str(payload.get("rateKind")));
        l.setBusinessDate(businessDate);
        l.setAmount(amount);
        l.setGrossFlag("N".equals(str(payload.get("grossFlag"))) ? "N" : "Y");
        l.setTaxCode(taxCode.trim().toUpperCase());
        l.setTaxRate(taxRate);
        l.setTaxableBase(taxableBase);
        l.setTaxAmount(taxAmount);
        l.setDeductible("SIMPLE".equals(calcType.toUpperCase()) ? "N"
                : ("N".equals(str(payload.get("deductible"))) ? "N" : "Y"));
        l.setOverTolerance(Boolean.TRUE.equals(payload.get("overTolerance")) ? "Y" : "N");
        l.setMultiCandidate(Boolean.TRUE.equals(payload.get("multiCandidate")) ? "Y" : "N");
        l.setStatus(TaxCalcLedger.STATUS_RECORDED);
        l.setRemark(str(payload.get("remark")));
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        l.setCreateDate(java.time.LocalDateTime.now());
        l.setVerNo(0);
        l.setDelFlag("0");
        ledgerDao.insert(l);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", l.getId());
        out.put("status", l.getStatus());
        out.put("taxAmount", l.getTaxAmount());
        return out;
    }

    @Override
    public List<TaxCalcLedger> list(String status, String trxType, String taxCode, String period) {
        LambdaQueryWrapper<TaxCalcLedger> q = new LambdaQueryWrapper<TaxCalcLedger>()
                .eq(TaxCalcLedger::getDelFlag, "0")
                .orderByDesc(TaxCalcLedger::getCreateDate);
        if (hasText(status)) {
            q.eq(TaxCalcLedger::getStatus, status.trim().toUpperCase());
        }
        if (hasText(trxType)) {
            q.eq(TaxCalcLedger::getTrxType, trxType.trim().toUpperCase());
        }
        if (hasText(taxCode)) {
            q.eq(TaxCalcLedger::getTaxCode, taxCode.trim().toUpperCase());
        }
        if (hasText(period)) {
            q.eq(TaxCalcLedger::getPeriod, period.trim());
        }
        return ledgerDao.selectList(q);
    }

    // ---------- 3.2 入账申请 + 凭证 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxCalcLedger submitApply(String id, String counterparty, String period, String summary) {
        TaxCalcLedger l = require(id);
        if (TaxCalcLedger.STATUS_VOUCHED.equals(l.getStatus())) {
            throw new ServiceException(422, "该行已挂凭证，不可重复入账");
        }
        if (!hasText(counterparty) || !hasText(period) || !hasText(summary)) {
            throw new ServiceException(422, "入账申请须补录对手方、期间（YYYYMM）、摘要三字段（FR-4.14-1-4）");
        }
        if (!period.trim().matches("\\d{6}")) {
            throw new ServiceException(422, "期间格式须为 YYYYMM");
        }
        l.setCounterparty(counterparty.trim());
        l.setPeriod(period.trim());
        l.setSummary(summary.trim());
        l.setStatus(TaxCalcLedger.STATUS_APPLYING);
        l.setUpdateBy(SecurityUtils.getCurrentUserId());
        l.setUpdateDate(java.time.LocalDateTime.now());
        ledgerDao.updateById(l);
        return l;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateVoucher(String id) {
        TaxCalcLedger l = require(id);
        if (!TaxCalcLedger.STATUS_APPLYING.equals(l.getStatus())) {
            throw new ServiceException(422, "仅入账申请（已补录对手方/期间/摘要）可生成凭证（当前 "
                    + l.getStatus() + "）");
        }
        BigDecimal tax = l.getTaxAmount();
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        String summaryBase = "计税入账 " + l.getTaxCode() + " " + l.getSummary();
        if ("SALES".equals(l.getTrxType())) {
            // 销项：借 1122 应收账款 / 贷 222102 销项税（D4 修正：借贷平衡挂账分录）
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1122", "DR", tax, summaryBase + "（" + l.getCounterparty() + "）"));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("222102", "CR", tax, summaryBase));
        } else if ("N".equals(l.getDeductible())) {
            // SIMPLE 不得抵扣：借 660299 费用 / 贷 2202 应付账款
            lines.add(GlVoucherService.FinVoucherLineSpec.of("660299", "DR", tax, summaryBase + "（简易计税不得抵扣 BR-4.14-08）"));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("2202", "CR", tax, summaryBase + "（" + l.getCounterparty() + "）"));
        } else {
            // 进项可抵扣：借 222101 进项税 / 贷 2202 应付账款
            lines.add(GlVoucherService.FinVoucherLineSpec.of("222101", "DR", tax, summaryBase));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("2202", "CR", tax, summaryBase + "（" + l.getCounterparty() + "）"));
        }
        LocalDate vDate = lastDayOf(l.getPeriod());
        var v = voucherService.create("TAXCALC", vDate, summaryBase,
                "TAXCALC", l.getId(), null, lines, true); // DRAFT 三态
        l.setVoucherId(v.getId());
        l.setStatus(TaxCalcLedger.STATUS_VOUCHED);
        l.setUpdateBy(SecurityUtils.getCurrentUserId());
        l.setUpdateDate(java.time.LocalDateTime.now());
        ledgerDao.updateById(l);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledgerId", l.getId());
        out.put("voucherId", v.getId());
        out.put("voucherNo", v.getVoucherNo());
        out.put("status", v.getStatus()); // DRAFT——审核过账后方影响总账（制单≠审核）
        return out;
    }

    // ---------- 4.1 合规校验报告（核心能力 4） ----------

    @Override
    public Map<String, Object> complianceReport(String period) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = new ArrayList<>();

        // ① 税码过期/临期引用扫描（红=已过期仍被引用；黄=距失效 ≤30 天）
        Set<String> usedCodes = new LinkedHashSet<>();
        for (TaxCalcLedger row : ledgerDao.selectList(new LambdaQueryWrapper<TaxCalcLedger>()
                .eq(TaxCalcLedger::getDelFlag, "0"))) {
            usedCodes.add(row.getTaxCode());
        }
        List<String> expiredRefs = new ArrayList<>();
        List<String> nearingRefs = new ArrayList<>();
        for (String code : usedCodes) {
            Map<String, Object> t = taxCodeService.trial(code, today);
            if (!Boolean.TRUE.equals(t.get("applicable"))) {
                expiredRefs.add(code);
                continue;
            }
            Object exp = t.get("expireDate");
            if (exp != null) {
                LocalDate expDate = LocalDate.parse(exp.toString());
                if (!expDate.isBefore(today) && !expDate.isAfter(today.plusDays(30))) {
                    nearingRefs.add(code + "（失效日 " + expDate + "）");
                }
            }
        }
        items.add(item("taxCodeExpiry", expiredRefs.isEmpty() ? (nearingRefs.isEmpty() ? "ok" : "warn") : "red",
                expiredRefs.isEmpty() ? (nearingRefs.isEmpty() ? "无过期/临期引用"
                        : "临期税码仍被引用：" + String.join("、", nearingRefs))
                        : "已过期税码仍被台账引用：" + String.join("、", expiredRefs),
                expiredRefs.isEmpty() ? "临期税码请主数据管理员提前维护延续区间（BR-4.1-17）"
                        : "立即维护覆盖当前日期的税码记录，历史引用行须复核重算"));

        // ② 税收分类编码登记检查（黄标铺垫，本期不阻断——BR-4.14-14 开票环节前置）
        items.add(item("taxCategoryCode", "warn",
                "税码主数据未登记税收分类编码（本期 OOS 铺垫，不阻断计税）",
                "开票环节（M9-2）启用前须在税码主数据补录税收分类编码（BR-4.14-14）"));

        // ③ 台账已过账行 vs 总账 222101/222102 勾稽（C-4.14-04 计税侧前置）
        String p = hasText(period) ? period.trim() : today.format(PERIOD_FMT);
        List<TaxCalcLedger> posted = ledgerDao.selectPostedByPeriod(p);
        BigDecimal ledgerInput = BigDecimal.ZERO;
        BigDecimal ledgerOutput = BigDecimal.ZERO;
        for (TaxCalcLedger row : posted) {
            if ("222101".equals(inputAcct(row))) {
                ledgerInput = ledgerInput.add(row.getTaxAmount());
            } else {
                ledgerOutput = ledgerOutput.add(row.getTaxAmount());
            }
        }
        BigDecimal glInput = balanceTotal("222101", p, "DR");
        BigDecimal glOutput = balanceTotal("222102", p, "CR");
        BigDecimal diffIn = ledgerInput.subtract(glInput).abs();
        BigDecimal diffOut = ledgerOutput.subtract(glOutput).abs();
        boolean reconOk = diffIn.signum() == 0 && diffOut.signum() == 0;
        List<Map<String, Object>> details = new ArrayList<>();
        if (!reconOk) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("period", p);
            d.put("ledgerInput", ledgerInput);
            d.put("glInput", glInput);
            d.put("diffInput", diffIn);
            d.put("ledgerOutput", ledgerOutput);
            d.put("glOutput", glOutput);
            d.put("diffOutput", diffOut);
            details.add(d);
        }
        Map<String, Object> recon = item("glRecon", reconOk ? "ok" : "red",
                reconOk ? "台账入账与总账 222101/222102 勾稽一致（期间 " + p + "）"
                        : "勾稽差异：进项差 " + diffIn + " / 销项差 " + diffOut + "（期间 " + p + "）",
                reconOk ? "—" : "逐项核对已过账 TAXCALC 凭证与总账发生额，查明差异后调整一致（C-4.14-04）");
        recon.put("details", details);
        items.add(recon);

        // ④ SIMPLE（不得抵扣）行入账到 222101 → 红标违规
        List<String> violations = new ArrayList<>();
        for (TaxCalcLedger row : ledgerDao.selectPostedNonDeductible()) {
            violations.add(row.getId() + "（税码 " + row.getTaxCode() + "，税额 " + row.getTaxAmount() + "）");
        }
        items.add(item("nonDeductibleViolation", violations.isEmpty() ? "ok" : "red",
                violations.isEmpty() ? "无简易计税抵扣违规"
                        : "不得抵扣行凭证进入 222101：" + String.join("、", violations),
                violations.isEmpty() ? "—" : "冲销错误凭证并按 660299 费用科目重新入账（BR-4.14-08）"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("items", items);
        long red = items.stream().filter(i -> "red".equals(i.get("level"))).count();
        long warn = items.stream().filter(i -> "warn".equals(i.get("level"))).count();
        out.put("redCount", red);
        out.put("warnCount", warn);
        out.put("allGreen", red == 0 && warn == 0);
        return out;
    }

    // ---------- helpers ----------

    /** 该行入账凭证的进项侧科目（勾稽分类用） */
    private String inputAcct(TaxCalcLedger row) {
        return "PURCHASE".equals(row.getTrxType()) && "Y".equals(row.getDeductible()) ? "222101" : "222102";
    }

    private BigDecimal balanceTotal(String accountCode, String period, String direction) {
        List<FinBalance> rows = balanceDao.selectList(new LambdaQueryWrapper<FinBalance>()
                .eq(FinBalance::getAccountCode, accountCode)
                .eq(FinBalance::getPeriod, period));
        BigDecimal total = BigDecimal.ZERO;
        for (FinBalance b : rows) {
            BigDecimal v = "DR".equals(direction) ? b.getDrTotal() : b.getCrTotal();
            if (v != null) {
                total = total.add(v);
            }
        }
        return total;
    }

    private Map<String, Object> item(String key, String level, String detail, String advice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("level", level);
        m.put("detail", detail);
        m.put("advice", advice);
        return m;
    }

    private TaxCalcLedger require(String id) {
        TaxCalcLedger l = ledgerDao.selectById(id);
        if (l == null || "1".equals(l.getDelFlag())) {
            throw new ServiceException(422, "台账行不存在：" + id);
        }
        return l;
    }

    private LocalDate lastDayOf(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return LocalDate.of(y, m, 1).withDayOfMonth(
                LocalDate.of(y, m, 1).lengthOfMonth());
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o, BigDecimal dflt) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return dflt;
        }
        return o instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(o));
    }

    private static LocalDate parseDate(String s) {
        if (!hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new ServiceException(422, "业务发生日期格式须为 yyyy-MM-dd");
        }
    }
}

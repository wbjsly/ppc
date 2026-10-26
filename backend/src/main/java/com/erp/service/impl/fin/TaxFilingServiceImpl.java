package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxReturnAdjDao;
import com.erp.dao.fin.TaxReturnDao;
import com.erp.dao.fin.TaxReturnLineDao;
import com.erp.entity.fin.TaxReturn;
import com.erp.entity.fin.TaxReturnAdj;
import com.erp.entity.fin.TaxReturnLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.TaxFilingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 纳税申报实现（M9-5-1/2，spec tax-filing，design D1~D3）。
 *
 * 卡控内生：
 *  - C-4.14-04/BR-4.14-30：勾稽差异>0 → L1 阻断 + 栏次级差异行（申报数/账载数/差额）；
 *    账载 = erp_fin_balance POSTED 净发生额（222101 借−贷 / 222102 贷−借 / 222103 贷−借，task 1.2 口径）；
 *  - BR-4.14-32：应纳 = 销项−进项+转出−上期留抵，负值结转留抵不生成退税；
 *  - BR-4.14-34：附加税随主税联动（城建 CITY_TAX_RATE 默认 7% + 教育 3% + 地方 2%），主税零则全零；
 *  - BR-4.14-31：锁定期（APPROVED 起）修改走跨期调整单（前后快照 + 审批）；
 *  - 逾期：滞纳金 = 滞纳税款 × 0.05% × 滞纳天数，升级财务经理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxFilingServiceImpl implements TaxFilingService {

    public static final String BIZ_TYPE = "TaxFiling";
    public static final String BIZ_TYPE_ADJ = "TaxFilingAdj";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 附加税费率（BR-4.14-34）：城建（默认 7%，CITY_TAX_RATE 可配）/教育/地方教育 */
    private static final BigDecimal EDU_RATE = new BigDecimal("0.03");
    private static final BigDecimal LOCAL_EDU_RATE = new BigDecimal("0.02");
    private static final BigDecimal LATE_FEE_DAILY = new BigDecimal("0.0005");

    private final TaxReturnDao returnDao;
    private final TaxReturnLineDao lineDao;
    private final TaxReturnAdjDao adjDao;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final com.erp.service.system.NoticeService noticeService;
    private final JdbcTemplate jdbc;

    /** 优惠适用校验器（add-tax-risk-control D2：字段注入避免构造器断裂既有 DbTest） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.erp.service.fin.TaxPreferenceValidator taxPreferenceValidator;

    // ================================================================
    // 2.1 进销项数据汇总（FR-4.14-5-2 / BR-4.14-31）
    // ================================================================

    @Override
    public Map<String, Object> summarize(String period) {
        assertPeriod(period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);

        // 销项净额：期内已开具蓝票销项 − 期内红票销项（作废票天然不在）
        BigDecimal blueTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_sales_invoice "
                        + "WHERE DEL_FLAG='0' AND STATUS <> 'VOID' "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", BigDecimal.class, period));
        BigDecimal redTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_red_invoice "
                        + "WHERE DEL_FLAG='0' AND DATE_FORMAT(RED_DATE,'%Y%m') = ?", BigDecimal.class, period));
        BigDecimal outputTax = blueTax.subtract(redTax);
        out.put("outputTax", outputTax);
        out.put("blueTax", blueTax);
        out.put("redTax", redTax);

        // 附表1：销项按税率分档（净额口径）
        List<Map<String, Object>> byRate = jdbc.queryForList(
                "SELECT TAX_RATE AS rate, SUM(TAX_AMOUNT) AS tax, COUNT(*) AS cnt "
                        + "FROM erp_fin_sales_invoice WHERE DEL_FLAG='0' AND STATUS <> 'VOID' "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ? GROUP BY TAX_RATE ORDER BY TAX_RATE",
                period);
        out.put("outputByRate", byRate);

        // 进项按认证态分档（附表2）
        BigDecimal certifiedTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND CERT_STATUS='CERTIFIED' AND DEDUCTIBLE='Y' "
                        + "AND CERT_PERIOD = ?", BigDecimal.class, period));
        BigDecimal pendingTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND CERT_STATUS='PENDING_CERT' "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", BigDecimal.class, period));
        BigDecimal nonDeductibleTax = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TAX_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND CERT_STATUS IN ('NOT_DEDUCTIBLE','INVALID') "
                        + "AND COALESCE(CERT_PERIOD, DATE_FORMAT(INVOICE_DATE,'%Y%m')) = ?",
                BigDecimal.class, period));
        out.put("inputTax", certifiedTax);
        out.put("pendingTax", pendingTax);
        out.put("nonDeductibleTax", nonDeductibleTax);
        out.put("inputByCert", jdbc.queryForList(
                "SELECT CERT_STATUS AS certStatus, SUM(TAX_AMOUNT) AS tax, COUNT(*) AS cnt "
                        + "FROM erp_fin_input_invoice WHERE DEL_FLAG='0' "
                        + "AND COALESCE(CERT_PERIOD, DATE_FORMAT(INVOICE_DATE,'%Y%m')) = ? "
                        + "GROUP BY CERT_PERIOD, CERT_STATUS", period));

        // 本期转出（BR-4.14-22 累计回写口径）
        BigDecimal transferOut = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(TRANSFER_OUT_AMOUNT),0) FROM erp_fin_input_invoice "
                        + "WHERE DEL_FLAG='0' AND COALESCE(CERT_PERIOD, DATE_FORMAT(INVOICE_DATE,'%Y%m')) = ?",
                BigDecimal.class, period));
        out.put("transferOut", transferOut);

        // 上期期末留抵结转（BR-4.14-32）
        BigDecimal prevCredit = nvl(returnDao.selectPrevEndCredit(prevPeriod(period)));
        out.put("prevCredit", prevCredit);

        // BR-4.14-31 跨期提示：期内开具的红票，其原票所属期 ≠ 本期（申报归属提示，不计入当期销项以外数据）
        List<Map<String, Object>> crossPeriod = jdbc.queryForList(
                "SELECT r.RED_NO, r.ORIGIN_INVOICE_NO, DATE_FORMAT(i.INVOICE_DATE,'%Y%m') AS originPeriod "
                        + "FROM erp_fin_red_invoice r JOIN erp_fin_sales_invoice i ON i.ID = r.ORIGIN_INVOICE_ID "
                        + "WHERE r.DEL_FLAG='0' AND DATE_FORMAT(r.RED_DATE,'%Y%m') = ? "
                        + "AND DATE_FORMAT(i.INVOICE_DATE,'%Y%m') <> ?", period, period);
        out.put("crossPeriodRedFlush", crossPeriod);

        // 申报前准备（FR-4.14-5-1）：期内异常发票计数（查验失败/争议）
        long abnormal = nvlLong(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_sales_invoice WHERE DEL_FLAG='0' AND STATUS='DISPUTED' "
                        + "AND DATE_FORMAT(INVOICE_DATE,'%Y%m') = ?", Long.class, period));
        out.put("abnormalInvoices", abnormal);

        // 待过账提示（不改变阻断结论，仅报告用）
        out.put("pendingVoucherCount", nvlLong(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT l.VOUCHER_ID) FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                        + "WHERE v.PERIOD = ? AND v.STATUS <> 'POSTED' "
                        + "AND l.ACCOUNT_CODE IN ('222101','222102','222103')", Long.class, period)));
        return out;
    }

    // ================================================================
    // 2.2 勾稽校验（C-4.14-04 / BR-4.14-30）
    // ================================================================

    @Override
    public Map<String, Object> reconcile(String period) {
        assertPeriod(period);
        Map<String, Object> sum = summarize(period);
        BigDecimal outputDeclared = (BigDecimal) sum.get("outputTax");
        BigDecimal inputDeclared = (BigDecimal) sum.get("inputTax");
        BigDecimal transferDeclared = (BigDecimal) sum.get("transferOut");

        // 账载 = POSTED 净发生额（task 1.2 口径：跨 AUX_KEY 聚合）
        BigDecimal outputGl = acctNet(period, "222102", false);
        BigDecimal inputGl = acctNet(period, "222101", true);
        BigDecimal transferGl = acctNet(period, "222103", false);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(diffRow("销项税额", outputDeclared, outputGl));
        rows.add(diffRow("进项税额", inputDeclared, inputGl));
        rows.add(diffRow("进项税额转出", transferDeclared, transferGl));
        BigDecimal diffTotal = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            diffTotal = diffTotal.add((BigDecimal) row.get("diff"));
        }
        boolean passed = diffTotal.signum() == 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("passed", passed);
        out.put("rows", rows);
        out.put("diffTotal", diffTotal);
        out.put("pendingVoucherCount", sum.get("pendingVoucherCount"));
        return out;
    }

    // ================================================================
    // 2.3 申报表生成（FR-4.14-5-4 / BR-4.14-32 / BR-4.14-34）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(String period) {
        return generate(period, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(String period, Map<String, Object> payload) {
        assertPeriod(period);
        if (returnDao.selectByPeriodType(period, TaxReturn.TYPE_VAT) != null) {
            throw new ServiceException(409, "所属期 " + period + " 增值税申报单已存在，重申报请走跨期调整");
        }
        Map<String, Object> rec = reconcile(period);
        if (!Boolean.TRUE.equals(rec.get("passed"))) {
            StringBuilder msg = new StringBuilder("申报勾稽未通过，已阻断（C-4.14-04）：");
            for (Map<String, Object> row : (List<Map<String, Object>>) rec.get("rows")) {
                if (((BigDecimal) row.get("diff")).signum() != 0) {
                    msg.append("［").append(row.get("line")).append(" 申报数 ")
                            .append(strip((BigDecimal) row.get("declared")))
                            .append(" / 账载数 ").append(strip((BigDecimal) row.get("gl")))
                            .append(" / 差额 ").append(strip((BigDecimal) row.get("diff")))
                            .append("］");
                }
            }
            if (nvlInt(rec.get("pendingVoucherCount")) > 0) {
                msg.append("（另有 ").append(rec.get("pendingVoucherCount"))
                        .append(" 张涉税科目凭证待过账）");
            }
            throw new ServiceException(422, msg.toString());
        }
        // BR-4.14-05 优惠适用校验（add-tax-risk-control：即征即退须落在备案范围，越界 422）
        if (taxPreferenceValidator != null && payload != null) {
            taxPreferenceValidator.validateForFiling(period, TaxReturn.TYPE_VAT,
                    dec(payload.get("vatRefundAmount")), dec(payload.get("vatRefundIncome")), null);
        }
        Map<String, Object> sum = summarize(period);
        BigDecimal outputTax = (BigDecimal) sum.get("outputTax");
        BigDecimal inputTax = (BigDecimal) sum.get("inputTax");
        BigDecimal transferOut = (BigDecimal) sum.get("transferOut");
        BigDecimal prevCredit = (BigDecimal) sum.get("prevCredit");

        // BR-4.14-32：负值结转留抵
        BigDecimal computed = outputTax.subtract(inputTax).add(transferOut).subtract(prevCredit);
        BigDecimal payable = computed.signum() < 0 ? BigDecimal.ZERO : computed;
        BigDecimal endCredit = computed.signum() < 0 ? computed.abs() : BigDecimal.ZERO;

        TaxReturn r = new TaxReturn();
        r.setId(uuid());
        r.setReturnNo(nextNo("TRET", returnDao::selectNosByPrefix));
        r.setPeriod(period);
        r.setTaxType(TaxReturn.TYPE_VAT);
        r.setStatus(TaxReturn.ST_DRAFT);
        r.setOutputTax(outputTax);
        r.setInputTax(inputTax);
        r.setTransferOut(transferOut);
        r.setPrevCredit(prevCredit);
        r.setPayableTax(payable);
        r.setEndCredit(endCredit);
        r.setReconStatus(TaxReturn.RECON_PASSED);
        r.setReconDiffTotal(BigDecimal.ZERO);
        r.setLockFlag("0");
        r.setOverdueFlag("0");
        r.setEscalatedFlag("0");
        r.setAdjustedFlag("0");
        r.setCreateBy(currentUser());
        returnDao.insert(r);
        writeLines(r, sum, payable, endCredit);

        log.info("VAT return {} generated: period {} payable {} endCredit {}",
                r.getReturnNo(), period, payable, endCredit);
        return Map.of("id", r.getId(), "returnNo", r.getReturnNo(), "payableTax", payable,
                "endCredit", endCredit, "status", r.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateCit(String period, Map<String, Object> payload) {
        assertPeriod(period);
        if (returnDao.selectByPeriodType(period, TaxReturn.TYPE_CIT) != null) {
            throw new ServiceException(409, "所属期 " + period + " 所得税预缴申报单已存在");
        }
        // 账载利润总额：当年 1 期~本期 6xxx 损益聚合（task 1.2 口径）
        BigDecimal glProfit = aggregateGlProfit(period);
        BigDecimal manualBase = dec(payload.get("citBase"));
        BigDecimal citBase;
        if (manualBase != null) {
            // 手工录入仅作总账取数失败的兜底；与账载一致才放行（C-4.14-04 同口径）
            if (glProfit != null && manualBase.compareTo(glProfit) != 0) {
                throw new ServiceException(422, "申报勾稽未通过，已阻断（C-4.14-04）：［利润总额 申报数 "
                        + strip(manualBase) + " / 账载数 " + strip(glProfit) + " / 差额 "
                        + strip(manualBase.subtract(glProfit)) + "］");
            }
            citBase = manualBase;
        } else {
            if (glProfit == null) {
                throw new ServiceException(422, "总账无损益数据，请手工录入累计利润总额");
            }
            citBase = glProfit;
        }
        BigDecimal citAdjust = nvl(dec(payload.get("citAdjust")));
        BigDecimal citRate = sysParamService.getAmount("CIT_RATE", new BigDecimal("0.25"));
        BigDecimal citPrepaid = nvl(dec(payload.get("citPrepaid")));
        BigDecimal taxable = citBase.add(citAdjust);
        BigDecimal cumPayable = taxable.signum() <= 0 ? BigDecimal.ZERO
                : taxable.multiply(citRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal due = cumPayable.subtract(citPrepaid);
        if (due.signum() < 0) {
            due = BigDecimal.ZERO; // 负基数/多预缴不退（spec：亏损季度零申报，已预缴不退）
        }
        // BR-4.14-05 优惠适用校验（add-tax-risk-control：加计扣除须在备案口径内，越界 422）
        if (taxPreferenceValidator != null) {
            taxPreferenceValidator.validateForFiling(period, TaxReturn.TYPE_CIT,
                    null, null, dec(payload.get("rndSuperDeduction")));
        }

        TaxReturn r = new TaxReturn();
        r.setId(uuid());
        r.setReturnNo(nextNo("TRET", returnDao::selectNosByPrefix));
        r.setPeriod(period);
        r.setTaxType(TaxReturn.TYPE_CIT);
        r.setStatus(TaxReturn.ST_DRAFT);
        r.setCitBase(citBase);
        r.setCitAdjust(citAdjust);
        r.setCitRate(citRate);
        r.setCitCumPayable(cumPayable);
        r.setCitPrepaid(citPrepaid);
        r.setCitDue(due);
        r.setReconStatus(glProfit == null ? TaxReturn.RECON_PENDING : TaxReturn.RECON_PASSED);
        r.setLockFlag("0");
        r.setOverdueFlag("0");
        r.setEscalatedFlag("0");
        r.setAdjustedFlag("0");
        r.setCreateBy(currentUser());
        returnDao.insert(r);

        // 栏次留痕
        line(r.getId(), "CIT-累计利润总额", "累计利润总额", citBase,
                glProfit == null ? "手工录入（总账无数据）" : "总账 6xxx 损益聚合");
        line(r.getId(), "CIT-纳税调整", "纳税调整项汇总", citAdjust, "手工录入");
        line(r.getId(), "CIT-累计应纳", "累计应纳税额", cumPayable, "（利润+调整）×税率快照 " + citRate);
        line(r.getId(), "CIT-已预缴", "年内已预缴", citPrepaid, "手工录入");
        line(r.getId(), "CIT-本期应补退", "本期应补退税额", due, "累计应纳−已预缴（负值归零）");

        log.info("CIT return {} generated: period {} due {}", r.getReturnNo(), period, due);
        return Map.of("id", r.getId(), "returnNo", r.getReturnNo(), "citDue", due, "status", r.getStatus());
    }

    // ================================================================
    // 2.4 提交审批 + 锁定（FR-4.14-5-5）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String returnId) {
        TaxReturn r = require(returnId);
        if (!TaxReturn.ST_DRAFT.equals(r.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交审批（当前：" + r.getStatus() + "）");
        }
        if (TaxReturn.TYPE_VAT.equals(r.getTaxType())
                && !TaxReturn.RECON_PASSED.equals(r.getReconStatus())) {
            throw new ServiceException(422, "勾稽未通过（" + r.getReconStatus() + "），不可提交审批");
        }
        // 三级：税务专员复核 → 税务主管审核 → 财务经理终审（FR-4.14-5-5）
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE", "税务专员复核")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "税务主管审核")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "财务经理终审")));
        String title = (TaxReturn.TYPE_VAT.equals(r.getTaxType()) ? "增值税申报 " : "所得税预缴申报 ")
                + r.getReturnNo() + "（所属期 " + r.getPeriod() + "）";
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE, r.getId(), title, "ROLE_FIN_MGR", chain);
        r.setApprId(inst.getId());
        r.setStatus(TaxReturn.ST_PENDING_APPROVAL);
        returnDao.updateById(r);
        return Map.of("id", r.getId(), "returnNo", r.getReturnNo(), "apprId", inst.getId(),
                "apprNo", inst.getApprNo(), "chainSize", chain.size());
    }

    // ================================================================
    // 2.5 缴款回执 + 逾期事件（FR-4.14-5-6）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerStatus(String returnId, Map<String, Object> payload) {
        TaxReturn r = require(returnId);
        boolean lockedOk = r.getLockFlag() != null && "1".equals(r.getLockFlag());
        if (!lockedOk) {
            throw new ServiceException(422, "申报单未审批锁定，不可登记提交/回执/缴款");
        }
        String receiptNo = str(payload.get("receiptNo"));
        String payVoucherNo = str(payload.get("payVoucherNo"));
        // 顺序：提交（SUBMIT_DATE）→ 回执（RECEIPT_NO+FILE_DATE）→ 缴款（PAY_*）
        if (str(payload.get("submitDate")) != null) {
            r.setSubmitDate(LocalDate.parse(str(payload.get("submitDate"))));
            r.setStatus(TaxReturn.ST_SUBMITTED);
        }
        if (hasText(receiptNo)) {
            if (r.getSubmitDate() == null) {
                throw new ServiceException(422, "请先登记提交日期再登记回执");
            }
            r.setReceiptNo(receiptNo);
            if (str(payload.get("fileDate")) != null) {
                r.setFileDate(LocalDate.parse(str(payload.get("fileDate"))));
                r.setStatus(TaxReturn.ST_FILED);
            }
        }
        if (str(payload.get("payDate")) != null) {
            if (r.getFileDate() == null) {
                throw new ServiceException(422, "请先登记回执（申报成功）再登记缴款");
            }
            BigDecimal amount = dec(payload.get("payAmount"));
            if (amount == null) {
                throw new ServiceException(422, "缴款金额必填");
            }
            r.setPayDate(LocalDate.parse(str(payload.get("payDate"))));
            r.setPayAmount(amount);
            r.setPayVoucherNo(payVoucherNo);
            r.setStatus(TaxReturn.ST_PAID);
        }
        r.setUpdateBy(currentUser());
        returnDao.updateById(r);
        return Map.of("id", r.getId(), "returnNo", r.getReturnNo(), "status", r.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> markOverdue(String returnId, String faultNote) {
        TaxReturn r = require(returnId);
        if (r.getFileDate() != null || TaxReturn.ST_PAID.equals(r.getStatus())) {
            throw new ServiceException(422, "已申报/已缴款，不构成逾期");
        }
        if (!hasText(faultNote)) {
            throw new ServiceException(422, "逾期事件单须填写故障说明（证据留痕）");
        }
        int deadlineDay = sysParamService.getInt("FILING_DEADLINE_DAY", 15);
        // 申报期截止 = 所属期次月 deadlineDay；滞纳天数从次日起算
        LocalDate deadline = YearMonth.parse(r.getPeriod(), DateTimeFormatter.ofPattern("yyyyMM"))
                .plusMonths(1).atDay(deadlineDay);
        long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(deadline, LocalDate.now()));
        BigDecimal taxableBase = TaxReturn.TYPE_VAT.equals(r.getTaxType())
                ? nvl(r.getPayableTax()).add(nvl(r.getEndCredit())) : nvl(r.getCitDue());
        BigDecimal lateFee = taxableBase.multiply(LATE_FEE_DAILY)
                .multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
        r.setOverdueFlag("1");
        r.setOverdueDays((int) days);
        r.setLateFee(lateFee);
        r.setFaultNote(faultNote);
        r.setEscalatedFlag("1");
        r.setStatus(TaxReturn.ST_OVERDUE);
        r.setUpdateBy(currentUser());
        returnDao.updateById(r);
        // 升级财务经理（FR-4.14-5-6 异常）
        noticeService.push("ROLE_FIN_MGR", null, "逾期申报事件 " + r.getReturnNo(),
                "所属期 " + r.getPeriod() + " 逾期 " + days + " 天，估算滞纳金 " + strip(lateFee)
                        + "；故障：" + faultNote, "TaxFiling", r.getId());
        log.info("tax return {} overdue: {} days, lateFee {}", r.getReturnNo(), days, lateFee);
        return Map.of("id", r.getId(), "returnNo", r.getReturnNo(), "overdueDays", days,
                "lateFee", lateFee, "status", r.getStatus());
    }

    // ================================================================
    // 2.4b 跨期调整（BR-4.14-31）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adjust(String returnId, Map<String, Object> payload) {
        TaxReturn r = require(returnId);
        if (!"1".equals(r.getLockFlag())) {
            throw new ServiceException(422, "申报单未锁定，未锁定申报单可直接重新生成");
        }
        String reason = str(payload.get("reason"));
        if (!hasText(reason)) {
            throw new ServiceException(422, "跨期调整原因必填（含是否需更正申报评估）");
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            TaxReturnAdj adj = new TaxReturnAdj();
            adj.setId(uuid());
            adj.setAdjNo(nextNo("TADJ", adjDao::selectNosByPrefix));
            adj.setReturnId(r.getId());
            adj.setPeriod(r.getPeriod());
            // 调整前快照 = 当前申报态；调整后 = 目标 payload（审批通过时 callback 应用）
            adj.setBeforeJson(mapper.writeValueAsString(Map.of(
                    "outputTax", nvl(r.getOutputTax()), "inputTax", nvl(r.getInputTax()),
                    "transferOut", nvl(r.getTransferOut()), "payableTax", nvl(r.getPayableTax()),
                    "endCredit", nvl(r.getEndCredit()))));
            adj.setAfterJson(mapper.writeValueAsString(payload));
            adj.setReason(reason);
            adj.setStatus(TaxReturnAdj.ST_DRAFT);
            adj.setCreateBy(currentUser());
            adjDao.insert(adj);
            // 二级审批（税务专员确认归属期 → 财务经理审批，BR-4.14-31 异常场景）
            List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE", "税务专员确认差异归属期")));
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "财务经理审批跨期调整")));
            ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE_ADJ, adj.getId(),
                    "申报跨期调整 " + adj.getAdjNo() + "（原单 " + r.getReturnNo() + "）",
                    "ROLE_FIN_MGR", chain);
            adj.setApprId(inst.getId());
            adj.setStatus(TaxReturnAdj.ST_PENDING_APPROVAL);
            adjDao.updateById(adj);
            return Map.of("adjId", adj.getId(), "adjNo", adj.getAdjNo(), "apprId", inst.getId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "调整数据序列化失败");
        }
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<TaxReturn> page(long current, long size, String period, String taxType, String status) {
        return returnDao.selectPage(new Page<>(current, size), new LambdaQueryWrapper<TaxReturn>()
                .eq(TaxReturn::getDelFlag, "0")
                .eq(hasText(period), TaxReturn::getPeriod, period)
                .eq(hasText(taxType), TaxReturn::getTaxType, taxType)
                .eq(hasText(status), TaxReturn::getStatus, status)
                .orderByDesc(TaxReturn::getPeriod, TaxReturn::getTaxType));
    }

    @Override
    public Map<String, Object> detail(String returnId) {
        TaxReturn r = require(returnId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", r);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<TaxReturnLine>()
                .eq(TaxReturnLine::getReturnId, returnId)
                .orderByAsc(TaxReturnLine::getLineCode)));
        if (hasText(r.getApprId())) {
            out.put("approval", approvalEngine.logs(BIZ_TYPE, returnId));
        }
        out.put("adjustments", adjDao.selectList(new LambdaQueryWrapper<TaxReturnAdj>()
                .eq(TaxReturnAdj::getReturnId, returnId)
                .orderByDesc(TaxReturnAdj::getCreateDate)));
        return out;
    }

    // ================================================================
    // 6.1 申报归档（FR-4.14-5-7：按 PERIOD 聚合，不落副本；≥10 年标注）
    // ================================================================

    @Override
    public Map<String, Object> archive(String period) {
        assertPeriod(period);
        // 仅归集已申报（FILED/PAID）产物；未申报期空（不产生空占位）
        List<TaxReturn> filed = returnDao.selectList(new LambdaQueryWrapper<TaxReturn>()
                .eq(TaxReturn::getDelFlag, "0")
                .eq(TaxReturn::getPeriod, period)
                .in(TaxReturn::getStatus, TaxReturn.ST_FILED, TaxReturn.ST_PAID)
                .orderByAsc(TaxReturn::getTaxType));
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaxReturn r : filed) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("returnNo", r.getReturnNo());
            item.put("taxType", r.getTaxType());
            item.put("status", r.getStatus());
            item.put("payableTax", r.getPayableTax());
            item.put("citDue", r.getCitDue());
            item.put("receiptNo", r.getReceiptNo());
            item.put("fileDate", r.getFileDate());
            item.put("payDate", r.getPayDate());
            item.put("payAmount", r.getPayAmount());
            item.put("payVoucherNo", r.getPayVoucherNo());
            item.put("lines", lineDao.selectList(new LambdaQueryWrapper<TaxReturnLine>()
                    .eq(TaxReturnLine::getReturnId, r.getId())
                    .orderByAsc(TaxReturnLine::getLineCode)));
            item.put("adjustments", adjDao.selectList(new LambdaQueryWrapper<TaxReturnAdj>()
                    .eq(TaxReturnAdj::getReturnId, r.getId())
                    .eq(TaxReturnAdj::getStatus, TaxReturnAdj.ST_APPROVED)));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("items", items);
        out.put("count", items.size());
        out.put("retentionYears", 10); // 保存期限 ≥10 年（FR-4.14-5-7）
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 栏次写入（BR-4.14-30 差异定位的结构基础） */
    private void writeLines(TaxReturn r, Map<String, Object> sum,
                             BigDecimal payable, BigDecimal endCredit) {
        line(r.getId(), "主表-销项税额", "销项税额", (BigDecimal) sum.get("outputTax"), "销项台账净额（蓝票−红票）");
        line(r.getId(), "主表-进项税额", "进项税额", (BigDecimal) sum.get("inputTax"), "进项台账已认证可抵扣");
        line(r.getId(), "主表-进项税额转出", "进项税额转出", (BigDecimal) sum.get("transferOut"), "进项台账转出累计");
        line(r.getId(), "主表-上期留抵", "上期期末留抵税额", (BigDecimal) sum.get("prevCredit"), "上期申报单结转");
        line(r.getId(), "主表-应纳税额", "应纳税额", payable, "销项−进项+转出−上期留抵（负值归零结转）");
        if (endCredit.signum() > 0) {
            line(r.getId(), "主表-期末留抵", "期末留抵税额", endCredit, "负值结转下期（BR-4.14-32，不自动退税）");
        }
        // 附表1：销项按税率
        for (Map<String, Object> row : (List<Map<String, Object>>) sum.get("outputByRate")) {
            BigDecimal rate = new BigDecimal(String.valueOf(row.get("rate")));
            line(r.getId(), "附表1-" + rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%",
                    "销售明细（税率 " + rate + "）", nvlDec(row.get("tax")),
                    row.get("cnt") + " 张发票");
        }
        // 附表2：进项按认证态
        for (Map<String, Object> row : (List<Map<String, Object>>) sum.get("inputByCert")) {
            line(r.getId(), "附表2-" + row.get("certStatus"), "进项明细（" + row.get("certStatus") + "）",
                    nvlDec(row.get("tax")), row.get("cnt") + " 张发票");
        }
        // 附加税（BR-4.14-34：主税为零则全零）
        BigDecimal cityRate = sysParamService.getAmount("CITY_TAX_RATE", new BigDecimal("0.07"));
        line(r.getId(), "附加税-城建", "城市维护建设税", payable.multiply(cityRate).setScale(2, RoundingMode.HALF_UP),
                "主税 × " + cityRate);
        line(r.getId(), "附加税-教育费附加", "教育费附加", payable.multiply(EDU_RATE).setScale(2, RoundingMode.HALF_UP),
                "主税 × 3%");
        line(r.getId(), "附加税-地方教育附加", "地方教育费附加", payable.multiply(LOCAL_EDU_RATE).setScale(2, RoundingMode.HALF_UP),
                "主税 × 2%");
    }

    private void line(String returnId, String code, String name, BigDecimal amount, String source) {
        TaxReturnLine l = new TaxReturnLine();
        l.setId(uuid());
        l.setReturnId(returnId);
        l.setLineCode(code);
        l.setLineName(name);
        l.setAmount(nvl(amount));
        l.setSourceDesc(source);
        l.setCreateBy("system");
        lineDao.insert(l);
    }

    /** 账载净发生额：direction=true 借−贷（进项），false 贷−借（销项/转出） */
    private BigDecimal acctNet(String period, String acct, boolean drMinusCr) {
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN ? THEN DR_TOTAL - CR_TOTAL ELSE CR_TOTAL - DR_TOTAL END),0) "
                        + "FROM erp_fin_balance WHERE PERIOD = ? AND ACCOUNT_CODE = ? AND DEL_FLAG='0'",
                BigDecimal.class, drMinusCr, period, acct);
        return nvl(net);
    }

    /** 当年累计利润总额（6xxx 损益统一 CR−DR：收入贷增为正、费用借增自然为负） */
    private BigDecimal aggregateGlProfit(String period) {
        String from = period.substring(0, 4) + "01";
        BigDecimal profit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CR_TOTAL - DR_TOTAL),0) "
                        + "FROM erp_fin_balance WHERE PERIOD BETWEEN ? AND ? AND DEL_FLAG='0' "
                        + "AND (ACCOUNT_CODE LIKE '6%')",
                BigDecimal.class, from, period);
        return nvl(profit);
    }

    private TaxReturn require(String returnId) {
        TaxReturn r = returnDao.selectById(returnId);
        if (r == null) {
            throw new ServiceException(404, "申报单不存在：" + returnId);
        }
        return r;
    }

    private static Map<String, Object> diffRow(String line, BigDecimal declared, BigDecimal gl) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("line", line);
        row.put("declared", nvl(declared));
        row.put("gl", nvl(gl));
        row.put("diff", nvl(declared).subtract(nvl(gl)));
        return row;
    }

    private static void assertPeriod(String period) {
        if (period == null || !period.matches("\\d{6}")) {
            throw new ServiceException(422, "申报所属期格式须为 yyyyMM：" + period);
        }
    }

    private static String prevPeriod(String period) {
        return YearMonth.parse(period, DateTimeFormatter.ofPattern("yyyyMM"))
                .minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().format(DAY) + "-";
        int max = 0;
        for (String no : query.byPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private interface PrefixQuery {
        List<String> byPrefix(String prefix);
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static long nvlLong(Object v) {
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }

    private static BigDecimal nvlDec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static int nvlInt(Object v) {
        return v == null ? 0 : Integer.parseInt(String.valueOf(v));
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "金额格式非法：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

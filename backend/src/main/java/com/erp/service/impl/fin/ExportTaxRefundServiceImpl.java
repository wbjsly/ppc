package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ExportDealDao;
import com.erp.dao.fin.ExportRefundApplyDao;
import com.erp.dao.fin.TaxReturnDao;
import com.erp.entity.fin.ExportDeal;
import com.erp.entity.fin.ExportRefundApply;
import com.erp.entity.fin.TaxReturn;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.ExportTaxRefundService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.system.NoticeService;
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
 * 出口退（免）税实现（M9-5-3，spec export-tax-refund，design D4/D5）。
 *
 * 卡控内生：
 *  - BR-4.14-41：CIF/CFR 扣运费/保险费/佣金折算 FOB 价；退税率按商品大类 SysParam
 *    `EXPORT_REFUND_RATE_<大类>`（默认 EXPORT_REFUND_RATE_DEFAULT），缺失 422（C-4.14-01）；
 *  - BR-4.14-03/C-4.14-13：三单缺一阻断（L2 豁免留痕）；超次年 4 月截止日 → 视同内销测算 + 升级；
 *  - BR-4.14-44：免抵退五值与同期增值税申报单 PAYABLE_TAX 勾稽不一致 L1 阻断；
 *  - BR-4.14-45：到账差异 > TOLERANCE_DEFAULT 挂「应收出口退税」核查不核销；
 *  - 预警查询型（design D5 不建 cron）：截止前 60/30 天两级 + 未申报档案清单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportTaxRefundServiceImpl implements ExportTaxRefundService {

    public static final String BIZ_TYPE_APPLY = "ExportRefundApply";
    public static final String BIZ_TYPE_EXEMPT = "ExportDealExemption";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 视同内销补税测算代理：FOB 折算额 × 13%（测算口径，非精确组成计税价格） */
    private static final BigDecimal DEEMED_SALE_RATE = new BigDecimal("0.13");

    private final ExportDealDao dealDao;
    private final ExportRefundApplyDao applyDao;
    private final TaxReturnDao returnDao;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;

    // ================================================================
    // 5.1 一票一档登记（FR-4.14-7-1 / BR-4.14-41）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerDeal(Map<String, Object> payload) {
        String invoiceNo = str(payload.get("invoiceNo"));
        String declNo = str(payload.get("declNo"));
        String declDateRaw = str(payload.get("declDate"));
        String tradeTerm = str(payload.get("tradeTerm"));
        BigDecimal grossAmount = dec(payload.get("grossAmount"));
        if (!hasText(invoiceNo) || !hasText(declNo) || !hasText(declDateRaw) || grossAmount == null
                || grossAmount.signum() <= 0) {
            throw new ServiceException(422, "一票一档要素必填（出口发票/报关单号/报关日期/成交价）");
        }
        String term = hasText(tradeTerm) ? tradeTerm.toUpperCase() : ExportDeal.TERM_FOB;
        if (!ExportDeal.TERM_FOB.equals(term) && !ExportDeal.TERM_CIF.equals(term)
                && !ExportDeal.TERM_CFR.equals(term)) {
            throw new ServiceException(422, "成交方式仅支持 FOB/CIF/CFR：" + tradeTerm);
        }
        LocalDate declDate = LocalDate.parse(declDateRaw);
        BigDecimal freight = nvl(dec(payload.get("freight")));
        BigDecimal insurance = nvl(dec(payload.get("insurance")));
        BigDecimal commission = nvl(dec(payload.get("commission")));
        BigDecimal fxRate = dec(payload.get("fxRate"));
        if (fxRate == null || fxRate.signum() <= 0) {
            throw new ServiceException(422, "折算汇率必填（出口当月 1 日口径）");
        }
        // BR-4.14-41：CIF/CFR 扣减运保费佣金折算 FOB
        BigDecimal fobOriginal = ExportDeal.TERM_FOB.equals(term) ? grossAmount
                : grossAmount.subtract(freight).subtract(insurance).subtract(commission);
        if (fobOriginal.signum() <= 0) {
            throw new ServiceException(422, "扣减运保费佣金后 FOB 价为负，请核对登记数据");
        }
        BigDecimal fobCny = fobOriginal.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);

        // 退税率：商品大类 SysParam 覆盖，缺失阻断（C-4.14-01 联动）
        String itemCategory = str(payload.get("itemCategory"));
        BigDecimal refundRate = hasText(itemCategory)
                ? rateOrNull("EXPORT_REFUND_RATE_" + itemCategory) : null;
        if (refundRate == null) {
            refundRate = rateOrNull("EXPORT_REFUND_RATE_DEFAULT");
        }
        if (refundRate == null) {
            throw new ServiceException(422, "退税率主数据缺失（商品大类 " + itemCategory
                    + " 无 EXPORT_REFUND_RATE_* 配置，C-4.14-01）");
        }
        BigDecimal refundTax = fobCny.multiply(refundRate).setScale(2, RoundingMode.HALF_UP);

        // 报关单与出口发票不一致 L4 预警（FR-4.14-7-1：金额/币别）
        BigDecimal declAmount = dec(payload.get("declAmount"));
        String declCurrency = str(payload.get("declCurrency"));
        String currency = hasText(str(payload.get("currency"))) ? str(payload.get("currency")) : "USD";
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.01"));
        boolean mismatch = (declAmount != null
                && declAmount.subtract(grossAmount).abs().compareTo(tolerance) > 0)
                || (hasText(declCurrency) && !declCurrency.equalsIgnoreCase(currency));

        ExportDeal d = new ExportDeal();
        d.setId(uuid());
        d.setDealNo(nextNo("EXP", dealDao::selectNosByPrefix));
        d.setCustomerId(str(payload.get("customerId")));
        d.setCustomerName(str(payload.get("customerName")));
        d.setInvoiceNo(invoiceNo.trim());
        d.setDeclNo(declNo.trim());
        d.setDeclDate(declDate);
        d.setTradeTerm(term);
        d.setCurrency(currency);
        d.setFxRate(fxRate);
        d.setGrossAmount(grossAmount);
        d.setFreight(freight);
        d.setInsurance(insurance);
        d.setCommission(commission);
        d.setFobCny(fobCny);
        d.setItemCategory(itemCategory);
        d.setRefundRate(refundRate);
        d.setRefundTax(refundTax);
        d.setDocDecl("0");
        d.setDocInvoice("0");
        d.setDocReceipt("0");
        d.setMismatchFlag(mismatch ? "1" : "0");
        if (mismatch) {
            d.setMismatchNote("报关单与出口发票不一致（金额差 "
                    + (declAmount == null ? "币别不符" : strip(declAmount.subtract(grossAmount)))
                    + (hasText(declCurrency) && !declCurrency.equalsIgnoreCase(currency)
                    ? "，币别 " + declCurrency + " vs " + currency : "") + "），待核实");
        }
        // 申报截止 = 报关次年 4 月 30 日（C-4.14-13 起算）
        d.setExpireDate(java.time.YearMonth.of(declDate.getYear() + 1, 4).atEndOfMonth());
        d.setCreateBy(currentUser());
        dealDao.insert(d);
        log.info("export deal {} registered: FOB {} rate {} refund {}",
                d.getDealNo(), fobCny, refundRate, refundTax);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.getId());
        out.put("dealNo", d.getDealNo());
        out.put("fobCny", fobCny);
        out.put("refundRate", refundRate);
        out.put("refundTax", refundTax);
        out.put("expireDate", d.getExpireDate());
        out.put("mismatch", mismatch);
        out.put("mismatchNote", d.getMismatchNote());
        return out;
    }

    // ================================================================
    // 5.2 单证齐全检查 + 豁免（FR-4.14-7-2 / BR-4.14-03 / C-4.14-13）
    // ================================================================

    @Override
    public Map<String, Object> checkDocuments(String dealId) {
        ExportDeal d = require(dealId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dealNo", d.getDealNo());
        out.put("docDecl", d.getDocDecl());
        out.put("docInvoice", d.getDocInvoice());
        out.put("docReceipt", d.getDocReceipt());
        out.put("docAgent", d.getDocAgent());

        List<String> missing = new ArrayList<>();
        if (!"1".equals(d.getDocDecl())) {
            missing.add("报关单");
        }
        if (!"1".equals(d.getDocInvoice())) {
            missing.add("出口发票");
        }
        if (!"1".equals(d.getDocReceipt())) {
            missing.add("收汇凭证");
        }
        boolean exempted = d.getRemark() != null && d.getRemark().startsWith("EXEMPT:");
        LocalDate today = LocalDate.now();
        boolean pastDeadline = d.getExpireDate() != null && d.getExpireDate().isBefore(today);
        boolean nearDeadline = d.getExpireDate() != null && !pastDeadline
                && !d.getExpireDate().isAfter(today.plusDays(
                        sysParamService.getInt("EXPORT_REFUND_WARN_DAYS2", 30)));

        out.put("missing", missing);
        out.put("exempted", exempted);
        out.put("pastDeadline", pastDeadline);
        out.put("nearDeadline", nearDeadline);
        out.put("blocked", !missing.isEmpty() && !exempted);
        if (pastDeadline && !missing.isEmpty()) {
            // C-4.14-13：已过截止日 → 视同内销补税测算 + 升级财务经理
            BigDecimal deemed = nvl(d.getFobCny()).multiply(DEEMED_SALE_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            out.put("deemedSaleTax", deemed);
            out.put("deemedSaleNote", "已过次年 4 月申报期截止日（" + d.getExpireDate()
                    + "），缺失单证 " + missing + "，视同内销补税测算 " + strip(deemed));
            noticeService.push("ROLE_FIN_MGR", null, "出口退税单证超期 " + d.getDealNo(),
                    String.valueOf(out.get("deemedSaleNote")), "ExportDeal", d.getId());
        }
        if (nearDeadline && !missing.isEmpty()) {
            out.put("warning", "距次年 4 月申报期截止日不足 30 天，缺失 " + missing);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExportDeal collectDocuments(String dealId, Map<String, Object> payload) {
        ExportDeal d = require(dealId);
        if (payload.get("docDecl") != null) {
            d.setDocDecl(Boolean.TRUE.equals(payload.get("docDecl")) ? "1" : String.valueOf(payload.get("docDecl")));
        }
        if (payload.get("docInvoice") != null) {
            d.setDocInvoice(Boolean.TRUE.equals(payload.get("docInvoice")) ? "1" : String.valueOf(payload.get("docInvoice")));
        }
        if (payload.get("docReceipt") != null) {
            d.setDocReceipt(Boolean.TRUE.equals(payload.get("docReceipt")) ? "1" : String.valueOf(payload.get("docReceipt")));
            if ("1".equals(d.getDocReceipt()) && str(payload.get("receiptDate")) != null) {
                d.setReceiptDate(LocalDate.parse(str(payload.get("receiptDate"))));
            }
        }
        d.setUpdateBy(currentUser());
        dealDao.updateById(d);
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExportDeal approveExemption(String dealId, String reason) {
        ExportDeal d = require(dealId);
        if (!hasText(reason)) {
            throw new ServiceException(422, "单证豁免须填写原因（税务主管确认留痕）");
        }
        d.setRemark("EXEMPT:" + reason + "（豁免人 " + currentUser() + " "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）");
        d.setUpdateBy(currentUser());
        dealDao.updateById(d);
        log.info("export deal {} exempted: {}", d.getDealNo(), reason);
        return d;
    }

    // ================================================================
    // 5.3 退税申报单（FR-4.14-7-4 / BR-4.14-44）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createApply(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        String idsRaw = str(payload.get("dealIds"));
        if (!hasText(period) || !hasText(idsRaw)) {
            throw new ServiceException(422, "退税申报单要素必填（所属期/一票一档 ID 列表）");
        }
        // 同期间退回补正挂起 → 暂停后续批次（FR-4.14-7-4 异常处理）
        if (applyDao.countReturnedInPeriod(period) > 0) {
            throw new ServiceException(422, "所属期 " + period + " 存在退回补正挂起的退税申报单，"
                    + "须完成补正后方可提交新批次");
        }
        List<String> ids = List.of(idsRaw.split(","));
        List<ExportDeal> deals = new ArrayList<>();
        BigDecimal fobTotal = BigDecimal.ZERO;
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (String id : ids) {
            ExportDeal d = dealDao.selectById(id.trim());
            if (d == null) {
                throw new ServiceException(404, "一票一档不存在：" + id);
            }
            if (hasText(d.getRefundApplyNo())) {
                throw new ServiceException(422, "档案 " + d.getDealNo() + " 已纳入退税申报单 "
                        + d.getRefundApplyNo());
            }
            // 单证齐全门禁（L2 豁免后放行）
            Map<String, Object> check = checkDocuments(d.getId());
            if (Boolean.TRUE.equals(check.get("blocked"))) {
                throw new ServiceException(422, "档案 " + d.getDealNo() + " 单证不齐（缺 "
                        + check.get("missing") + "），须补齐或经税务主管豁免后申报（BR-4.14-03）");
            }
            if (check.get("pastDeadline") != null && Boolean.TRUE.equals(check.get("pastDeadline"))
                    && Boolean.TRUE.equals(check.get("blocked"))) {
                throw new ServiceException(422, String.valueOf(check.get("deemedSaleNote")));
            }
            deals.add(d);
            fobTotal = fobTotal.add(nvl(d.getFobCny()));
            refundTotal = refundTotal.add(nvl(d.getRefundTax()));
        }
        // 免抵退勾稽（BR-4.14-44）：提供五值时与同期增值税申报单 PAYABLE_TAX 勾稽
        BigDecimal mtdPayable = dec(payload.get("mtdPayable"));
        String vatReturnId = str(payload.get("vatReturnId"));
        if (mtdPayable != null) {
            if (!hasText(vatReturnId)) {
                throw new ServiceException(422, "免抵退汇总须关联同期增值税申报单（BR-4.14-44）");
            }
            TaxReturn vat = returnDao.selectById(vatReturnId);
            if (vat == null || !period.equals(vat.getPeriod())
                    || !TaxReturn.TYPE_VAT.equals(vat.getTaxType())) {
                throw new ServiceException(422, "增值税申报单与所属期不符或不存在（BR-4.14-44）");
            }
            if (mtdPayable.compareTo(nvl(vat.getPayableTax())) != 0) {
                throw new ServiceException(422, "免抵退「当期应纳税额」" + strip(mtdPayable)
                        + " 与增值税申报表 " + strip(vat.getPayableTax()) + " 勾稽不一致，已阻断（BR-4.14-44）");
            }
        }

        ExportRefundApply a = new ExportRefundApply();
        a.setId(uuid());
        a.setApplyNo(nextNo("RFA", applyDao::selectNosByPrefix));
        a.setPeriod(period);
        a.setDealCount(deals.size());
        a.setFobTotal(fobTotal);
        a.setRefundTotal(refundTotal);
        a.setStatus(ExportRefundApply.ST_DRAFT);
        a.setExemptFlag("0");
        a.setMtdPayable(mtdPayable);
        a.setMtdRefundTotal(dec(payload.get("mtdRefundTotal")));
        a.setMtdRefundDue(dec(payload.get("mtdRefundDue")));
        a.setMtdExemptTax(dec(payload.get("mtdExemptTax")));
        a.setMtdEndCredit(dec(payload.get("mtdEndCredit")));
        a.setVatReturnId(vatReturnId);
        a.setCreateBy(currentUser());
        applyDao.insert(a);
        for (ExportDeal d : deals) {
            d.setRefundApplyNo(a.getApplyNo());
            dealDao.updateById(d);
        }
        log.info("export refund apply {} created: {} deals, refund {}",
                a.getApplyNo(), deals.size(), refundTotal);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "dealCount", deals.size(),
                "fobTotal", fobTotal, "refundTotal", refundTotal, "status", a.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitApply(String applyId) {
        ExportRefundApply a = requireApply(applyId);
        if (!ExportRefundApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交审批（当前：" + a.getStatus() + "）");
        }
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "出口退税申报审批（L2）")));
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE_APPLY, a.getId(),
                "出口退税申报 " + a.getApplyNo() + "（" + a.getDealCount() + " 票，应退 "
                        + strip(a.getRefundTotal()) + "）", "ROLE_FIN_MGR", chain);
        a.setApprId(inst.getId());
        applyDao.updateById(a);
        return Map.of("id", a.getId(), "apprId", inst.getId(), "apprNo", inst.getApprNo());
    }

    // ================================================================
    // 5.3b 申报状态机登记（FR-4.14-7-4）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerStatus(String applyId, Map<String, Object> payload) {
        ExportRefundApply a = requireApply(applyId);
        String target = str(payload.get("status"));
        if (!hasText(target)) {
            throw new ServiceException(422, "状态必填");
        }
        switch (target) {
            case "SUBMITTED" -> {
                requireFrom(a, ExportRefundApply.ST_DRAFT, ExportRefundApply.ST_RETURNED);
                a.setStatus(ExportRefundApply.ST_SUBMITTED);
            }
            case "ACCEPTED" -> {
                requireFrom(a, ExportRefundApply.ST_SUBMITTED);
                a.setStatus(ExportRefundApply.ST_ACCEPTED);
            }
            case "AUDITING" -> {
                requireFrom(a, ExportRefundApply.ST_ACCEPTED);
                a.setStatus(ExportRefundApply.ST_AUDITING);
            }
            case "APPROVED" -> {
                requireFrom(a, ExportRefundApply.ST_AUDITING);
                BigDecimal approved = dec(payload.get("approvedAmount"));
                if (approved == null) {
                    approved = a.getRefundTotal();
                }
                a.setApprovedAmount(approved);
                a.setStatus(ExportRefundApply.ST_APPROVED);
            }
            case "RETURNED" -> {
                requireFrom(a, ExportRefundApply.ST_SUBMITTED, ExportRefundApply.ST_ACCEPTED,
                        ExportRefundApply.ST_AUDITING, ExportRefundApply.ST_APPROVED);
                String reason = str(payload.get("returnReason"));
                if (!hasText(reason)) {
                    throw new ServiceException(422, "退回补正须填写退回原因");
                }
                // 结构化补正清单（原因码/涉及报关单号/补正期限）
                String corrections = str(payload.get("corrections"));
                if (!hasText(corrections)) {
                    List<String> declNos = new ArrayList<>();
                    dealDao.selectList(new LambdaQueryWrapper<ExportDeal>()
                                    .eq(ExportDeal::getRefundApplyNo, a.getApplyNo()))
                            .forEach(d -> declNos.add(d.getDeclNo()));
                    corrections = "[{\"reasonCode\":\"MANUAL\",\"reason\":\"" + reason.replace("\"", "'")
                            + "\",\"declNos\":[\"" + String.join("\",\"", declNos) + "\"],\"deadline\":\""
                            + LocalDate.now().plusDays(7) + "\"}]";
                }
                a.setReturnCorrectionsJson(corrections);
                a.setStatus(ExportRefundApply.ST_RETURNED);
            }
            case "REJECTED" -> {
                a.setStatus(ExportRefundApply.ST_REJECTED);
            }
            default -> throw new ServiceException(422, "非法状态登记：" + target);
        }
        a.setUpdateBy(currentUser());
        applyDao.updateById(a);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus());
    }

    // ================================================================
    // 5.4 到账核销（FR-4.14-7-5 / BR-4.14-45）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> receive(String applyId, Map<String, Object> payload) {
        ExportRefundApply a = requireApply(applyId);
        if (!ExportRefundApply.ST_APPROVED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅核准状态可登记到账（当前：" + a.getStatus() + "）");
        }
        BigDecimal received = dec(payload.get("receivedAmount"));
        if (received == null || received.signum() < 0) {
            throw new ServiceException(422, "到账金额必填且不得为负");
        }
        LocalDate receivedDate = str(payload.get("receivedDate")) != null
                ? LocalDate.parse(str(payload.get("receivedDate"))) : LocalDate.now();
        BigDecimal approved = nvl(a.getApprovedAmount());
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.01"));
        BigDecimal diff = received.subtract(approved);
        a.setReceivedAmount(received);
        a.setReceivedDate(receivedDate);
        if (diff.abs().compareTo(tolerance) <= 0) {
            // 一致 → 自动核销 + 收款凭证（借 银行存款 / 贷 应收出口退税）
            var lines = new ArrayList<GlVoucherService.FinVoucherLineSpec>();
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1002", "DR", received,
                    "出口退税到账-" + a.getApplyNo()));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("222101", "CR", received,
                    "出口退税到账-" + a.getApplyNo()));
            var voucher = voucherService.create("EXPR", receivedDate,
                    "出口退税到账核销：" + a.getApplyNo(), "EXPREFUND", a.getApplyNo(), null, lines, true);
            a.setVoucherId(voucher.getId());
            a.setStatus(ExportRefundApply.ST_PAID);
            a.setUpdateBy(currentUser());
            applyDao.updateById(a);
            log.info("export refund {} received {} = approved, reconciled", a.getApplyNo(), received);
            return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus(),
                    "reconciled", true, "voucherId", voucher.getId(), "diff", diff);
        }
        // BR-4.14-45：差额挂「应收出口退税」核查，不核销
        String taskId = uuid();
        a.setReconcileTaskId(taskId);
        a.setUpdateBy(currentUser());
        applyDao.updateById(a);
        noticeService.push("ROLE_FIN_MGR", null, "出口退税到账差异挂账 " + a.getApplyNo(),
                "到账 " + strip(received) + " vs 核准 " + strip(approved) + "，差额 " + strip(diff)
                        + "，已挂应收出口退税核查", "ExportRefundApply", a.getId());
        log.warn("export refund {} received {} diff {} held", a.getApplyNo(), received, diff);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus(),
                "reconciled", false, "reconcileTaskId", taskId, "diff", diff);
    }

    // ================================================================
    // 5.4b 分级预警（FR-4.14-7 SOP 要点）
    // ================================================================

    @Override
    public Map<String, Object> warnings() {
        LocalDate today = LocalDate.now();
        int days1 = sysParamService.getInt("EXPORT_REFUND_WARN_DAYS", 60);
        int days2 = sysParamService.getInt("EXPORT_REFUND_WARN_DAYS2", 30);
        List<ExportDeal> level1 = dealDao.selectExpiringSoon(today, today.plusDays(days1));
        List<ExportDeal> level2 = dealDao.selectExpiringSoon(today, today.plusDays(days2));
        List<ExportDeal> overdue = dealDao.selectList(new LambdaQueryWrapper<ExportDeal>()
                .eq(ExportDeal::getDelFlag, "0")
                .isNull(ExportDeal::getRefundApplyNo)
                .isNotNull(ExportDeal::getExpireDate)
                .lt(ExportDeal::getExpireDate, today)
                .orderByAsc(ExportDeal::getExpireDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today);
        out.put("level1Days", days1);
        out.put("level2Days", days2);
        out.put("level1", level1);
        out.put("level2", level2);
        out.put("overdue", overdue);
        out.put("level1Count", level1.size());
        out.put("level2Count", level2.size());
        out.put("overdueCount", overdue.size());
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<ExportDeal> dealPage(long current, long size, String keyword, String refundApplyNo) {
        return dealDao.selectPage(new Page<>(current, size), new LambdaQueryWrapper<ExportDeal>()
                .eq(ExportDeal::getDelFlag, "0")
                .eq(hasText(refundApplyNo), ExportDeal::getRefundApplyNo, refundApplyNo)
                .and(hasText(keyword), w -> w.like(ExportDeal::getDealNo, keyword)
                        .or().like(ExportDeal::getInvoiceNo, keyword)
                        .or().like(ExportDeal::getDeclNo, keyword))
                .orderByDesc(ExportDeal::getCreateDate));
    }

    @Override
    public Page<ExportRefundApply> applyPage(long current, long size, String status) {
        return applyDao.selectPage(new Page<>(current, size), new LambdaQueryWrapper<ExportRefundApply>()
                .eq(ExportRefundApply::getDelFlag, "0")
                .eq(hasText(status), ExportRefundApply::getStatus, status)
                .orderByDesc(ExportRefundApply::getCreateDate));
    }

    @Override
    public Map<String, Object> applyDetail(String applyId) {
        ExportRefundApply a = requireApply(applyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        out.put("deals", dealDao.selectList(new LambdaQueryWrapper<ExportDeal>()
                .eq(ExportDeal::getRefundApplyNo, a.getApplyNo())
                .orderByAsc(ExportDeal::getDealNo)));
        if (hasText(a.getApprId())) {
            out.put("approval", approvalEngine.logs(BIZ_TYPE_APPLY, applyId));
        }
        return out;
    }

    private void requireFrom(ExportRefundApply a, String... allowed) {
        for (String s : allowed) {
            if (s.equals(a.getStatus())) {
                return;
            }
        }
        throw new ServiceException(422, "当前状态 " + a.getStatus() + " 不允许该流转（允许："
                + String.join("/", allowed) + "）");
    }

    private ExportDeal require(String dealId) {
        ExportDeal d = dealDao.selectById(dealId);
        if (d == null) {
            throw new ServiceException(404, "一票一档不存在：" + dealId);
        }
        return d;
    }

    private ExportRefundApply requireApply(String applyId) {
        ExportRefundApply a = applyDao.selectById(applyId);
        if (a == null) {
            throw new ServiceException(404, "退税申报单不存在：" + applyId);
        }
        return a;
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

    /** 退税率参数读取（未登记返回 null → C-4.14-01 阻断） */
    private BigDecimal rateOrNull(String key) {
        String v = sysParamService.getValue(key);
        if (!hasText(v)) {
            return null;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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

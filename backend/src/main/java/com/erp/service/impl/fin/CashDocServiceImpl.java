package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.CashWriteoffDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPaymentRequestDao;
import com.erp.dao.qms.ScarDeductionDao;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.fin.CashDocService;
import com.erp.service.fin.CashWriteoffService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.SupplierStatementService;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一收付款中心实现（spec payment-collection，design D1/D3）。
 * 过账 = 余额双向条件维护 + 单张资金凭证（RCT/PAY/PPR）+ FIFO 核销（跨期挂审批），失败全回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashDocServiceImpl implements CashDocService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> PAY_METHODS =
            List.of("电汇", "支票", "商业汇票", "银行承兑汇票", "现金");
    /** 预收账款（design D3：2203 已被应付暂估占用） */
    private static final String ACCT_AR = "1122";
    private static final String ACCT_BANK = "1002";
    private static final String ACCT_AP = "2202";
    private static final String ACCT_PREPAY = "1123";
    private static final String ACCT_PRERECEIPT = "2205";
    /** 凭证类型（RCT 收款 / PAY 付款 / PPR 预付，spec 过账资金凭证） */
    private static final String TYPE_RCT = "RCT";

    private final CashDocDao docDao;
    private final CashWriteoffDao woDao;
    private final FinBankAccountDao bankDao;
    private final FinApInvoiceDao invoiceDao;
    private final FinPaymentRequestDao reqDao;
    private final ScarDeductionDao deductionDao;
    private final GlVoucherService voucherService;
    private final CashWriteoffService writeoffService;
    private final SupplierStatementService statementService;
    private final com.erp.service.fin.ApPlanService apPlanService;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ================================================================
    // 创建（spec 统一收付款单）
    // ================================================================

    @Override
    @Transactional
    public CashDoc create(Map<String, Object> payload) {
        requireAny("创建收付款单", "ROLE_FINANCE_MGR");
        String direction = str(payload.get("direction"));
        if (!CashDoc.DIR_IN.equals(direction) && !CashDoc.DIR_OUT.equals(direction)) {
            throw new ServiceException(422, "方向仅支持 IN/OUT");
        }
        boolean inbound = CashDoc.DIR_IN.equals(direction);
        String partyId = str(payload.get("partyId"));
        if (!hasText(partyId)) {
            throw new ServiceException(422, inbound ? "客户必填（BR-4.6-17）" : "供应商必填（BR-4.6-17）");
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "金额必须大于 0");
        }
        String payMethod = str(payload.get("payMethod"));
        if (!PAY_METHODS.contains(payMethod)) {
            throw new ServiceException(422, "支付方式仅支持 " + String.join("/", PAY_METHODS) + "（BR-4.6-20）");
        }
        FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
        if (bank == null) {
            throw new ServiceException(422, "银行账户必填");
        }
        if (!FinBankAccount.ST_ACTIVE.equals(bank.getStatus())) {
            throw new ServiceException(422, "银行账户已停用：" + bank.getAccountName());
        }
        LocalDate payDate;
        try {
            payDate = LocalDate.parse(str(payload.get("payDate")));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "收付款日期格式须为 yyyy-MM-dd");
        }
        if (!inbound) {
            statementService.assertNotFrozen(partyId);
        }

        CashDoc doc = new CashDoc();
        doc.setId(uuid());
        doc.setDocNo(nextDocNo(inbound));
        doc.setDirection(direction);
        doc.setPartyType(inbound ? CashDoc.PARTY_CUSTOMER : CashDoc.PARTY_SUPPLIER);
        doc.setPartyId(partyId);
        doc.setPartyCode(str(payload.get("partyCode")));
        doc.setPartyName(str(payload.get("partyName")));
        doc.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        doc.setDeductAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        doc.setActualAmount(amount.setScale(2, RoundingMode.HALF_UP));
        doc.setCurrency(hasText(str(payload.get("currency"))) ? str(payload.get("currency")) : "CNY");
        doc.setPayMethod(payMethod);
        doc.setBankAccountId(bank.getId());
        doc.setAccountName(bank.getAccountName());
        doc.setAccountNo(bank.getAccountNo());
        doc.setPayDate(payDate);
        doc.setStatus(CashDoc.ST_PENDING_POST);
        doc.setCrossPeriod("0");
        doc.setWriteoffState(CashDoc.WO_NONE);
        doc.setRemark(str(payload.get("remark")));
        doc.setCreateBy(currentUser());
        docDao.insert(doc);
        return doc;
    }

    // ================================================================
    // 过账（spec 过账资金凭证 + 统一 FIFO 核销）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> post(String id) {
        requireAny("过账收付款单", "ROLE_FINANCE_MGR");
        CashDoc doc = docDao.selectById(id);
        if (doc == null) {
            throw new ServiceException(422, "收付款单不存在");
        }
        if (!CashDoc.ST_PENDING_POST.equals(doc.getStatus())) {
            throw new ServiceException(422, "仅待过账单据可过账：" + doc.getStatus());
        }
        boolean inbound = CashDoc.DIR_IN.equals(doc.getDirection());

        // OUT 侧前置：关联申请额度卡控（C-4.6-09）与付款计划（add-ap-management D4）
        FinPaymentRequest req = null;
        if (!inbound && hasText(doc.getReqId())) {
            req = reqDao.selectById(doc.getReqId());
            if (req != null) {
                BigDecimal executed = nvl(docDao.selectExecutedByReq(doc.getReqId()));
                if (executed.add(doc.getAmount()).compareTo(nvl(req.getApplyAmount())) > 0) {
                    throw new ServiceException(422, "付款金额超过审批金额（累计已付 "
                            + executed.toPlainString() + " / 审批 " + nvl(req.getApplyAmount()).toPlainString()
                            + "）（C-4.6-09 L1 硬阻断）");
                }
                apPlanService.assertWithinPlan(doc.getPayDate()
                        .format(DateTimeFormatter.ofPattern("yyyyMM")), doc.getAmount());
            }
        }

        // FIFO 核销（含币种断言；跨期时核销行落库、PAID 回写挂起）；预付单不核销发票
        Map<String, Object> settled;
        if (!inbound && CashDoc.TYPE_PREPAY.equals(doc.getPayType())) {
            settled = new LinkedHashMap<>();
            settled.put("writeoffTotal", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            settled.put("remaining", nvl(doc.getAmount()));
            settled.put("crossPeriod", false);
            settled.put("rowCount", 0);
        } else {
            List<FinApInvoice> selected = null;
            if (!inbound && req != null) {
                selected = loadReqInvoices(req);
            }
            settled = writeoffService.settle(doc, selected);
        }
        BigDecimal writeoffTotal = (BigDecimal) settled.get("writeoffTotal");
        BigDecimal remaining = (BigDecimal) settled.get("remaining");
        boolean crossPeriod = Boolean.TRUE.equals(settled.get("crossPeriod"));

        // 余额双向条件维护（BR-4.6-21：OUT 扣减防透支 / IN 入账须 ACTIVE）
        BigDecimal cashFlow = nvl(doc.getActualAmount());
        FinBankAccount bank = bankDao.selectById(doc.getBankAccountId());
        // add-fx-revaluation task 3.2：单币种账户模型——单据币种须与账户币种一致，
        // 否则外币额会误维护他币余额（C-4.6-11 币种断言在账户维度的对称延伸）
        if (bank == null) {
            throw new ServiceException(422, "银行账户不存在：" + doc.getBankAccountId());
        }
        if (!bank.getCurrency().equals(doc.getCurrency())) {
            throw new ServiceException(422, "单据币种 " + doc.getCurrency()
                    + " 与银行账户币种 " + bank.getCurrency() + " 不一致：" + doc.getAccountName());
        }
        // 本位币折算率：CNY 账户=1（行为与旧版等价）；外币账户=开户日固化记账汇率
        BigDecimal rate = nvl(bank.getBookingRate());
        if (inbound) {
            if (bankDao.addBalance(doc.getBankAccountId(), cashFlow) == 0) {
                throw new ServiceException(422, "银行账户不可入账（已停用/不存在）：" + doc.getAccountName());
            }
        } else if (cashFlow.signum() > 0) {
            if (bankDao.deductBalance(doc.getBankAccountId(), cashFlow) == 0) {
                throw new ServiceException(422, "银行账户余额不足：" + doc.getAccountName()
                        + " 当前余额 " + nvl(bank != null ? bank.getBalance() : null).toPlainString()
                        + "，需实付 " + cashFlow.toPlainString());
            }
        }

        // 单张资金凭证（spec 过账资金凭证；核销不再产凭证）
        FinVoucher v = voucherService.create(
                inbound ? TYPE_RCT : (CashDoc.TYPE_PREPAY.equals(doc.getPayType())
                        ? FinVoucher.TYPE_PREPAY : FinVoucher.TYPE_PAY),
                doc.getPayDate(),
                (inbound ? "收款单：" : "付款单：") + doc.getDocNo(),
                inbound ? "CASH_RECEIPT" : "CASH_PAYMENT",
                doc.getDocNo(), inbound ? null : doc.getPartyId(),
                voucherLines(doc, inbound, writeoffTotal, remaining, rate));

        doc.setVoucherId(v.getId());
        doc.setStatus(CashDoc.ST_POSTED);
        if (crossPeriod) {
            doc.setCrossPeriod("1");
            doc.setWriteoffState(CashDoc.WO_PENDING);
        } else if (settled.get("rowCount") != null && ((Integer) settled.get("rowCount")) > 0) {
            doc.setWriteoffState(CashDoc.WO_APPROVED); // 同期免审，直接可执行
        }
        doc.setUpdateBy(currentUser());
        if (docDao.updateById(doc) == 0) {
            throw new ServiceException(409, "收付款单过账状态更新冲突");
        }

        // 同期核销立即回写；跨期挂审批（C-4.6-02）
        if (crossPeriod) {
            ((CashWriteoffServiceImpl) writeoffService).submitCrossApproval(doc);
        } else if (((Integer) settled.getOrDefault("rowCount", 0)) > 0) {
            writeoffService.applyApprovedSettle(doc.getId());
        }

        // OUT：SCAR 抵扣落账 + 申请累计执行（payment-execution 口径保留）
        if (!inbound) {
            settleDeductions(doc);
            if (req != null) {
                markRequestPaid(req);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", doc.getId());
        out.put("docNo", doc.getDocNo());
        out.put("voucherNo", v.getVoucherNo());
        out.put("writeoffTotal", writeoffTotal);
        out.put("remaining", remaining);
        out.put("crossPeriod", crossPeriod);
        out.put("status", doc.getStatus());
        log.info("cash doc {} posted: in={} amount={} writeoff={} remaining={} cross={} voucher={}",
                doc.getDocNo(), inbound, doc.getAmount(), writeoffTotal, remaining, crossPeriod, v.getVoucherNo());
        return out;
    }

    // ================================================================
    // 付款执行薄壳入口（PaymentServiceImpl 委托，保持旧 API 返回形态）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> executeOut(Map<String, Object> payload) {
        requireAny("执行付款", "ROLE_ADMIN");
        String reqId = str(payload.get("reqId"));
        FinPaymentRequest r = reqDao.selectById(reqId);
        if (r == null) {
            throw new ServiceException(422, "付款申请不存在");
        }
        if (!FinPaymentRequest.ST_SCHEDULED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅排期中的申请可执行付款（待付款须先恢复排期）：" + r.getStatus());
        }
        // SCAR 抵扣校验（payment-execution 口径；deductIds 兼容 List 与 JSON 串两种入参形态）
        Object rawDeduct = payload.get("deductIds");
        List<String> deductIds;
        if (rawDeduct instanceof List<?> list) {
            deductIds = new ArrayList<>();
            for (Object o : list) {
                deductIds.add(String.valueOf(o));
            }
        } else {
            deductIds = strList(str(rawDeduct));
        }
        BigDecimal deductSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (String did : deductIds) {
            ScarDeduction d = deductionDao.selectById(did);
            if (d == null) {
                throw new ServiceException(422, "扣款单不存在：" + did);
            }
            if (!"TO_DEDUCT".equals(d.getStatus())) {
                throw new ServiceException(422, "扣款单状态不可抵扣（须待抵扣，争议/未确认不可）：" + d.getDeductNo());
            }
            if (!r.getSupplierId().equals(d.getSupplierId())) {
                throw new ServiceException(422, "扣款单供应商与付款申请不一致：" + d.getDeductNo());
            }
            deductSum = deductSum.add(nvl(d.getAmount()));
        }
        BigDecimal amount = dec(payload.get("applyAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "付款金额必须大于 0");
        }
        if (deductSum.signum() > 0 && deductSum.compareTo(amount) > 0) {
            throw new ServiceException(422, "抵扣合计 " + deductSum.toPlainString()
                    + " 超过付款金额 " + amount.toPlainString());
        }

        Map<String, Object> createPayload = new LinkedHashMap<>();
        createPayload.put("direction", CashDoc.DIR_OUT);
        createPayload.put("partyId", r.getSupplierId());
        createPayload.put("partyName", r.getSupplierName());
        createPayload.put("amount", amount);
        createPayload.put("payMethod", payload.get("payMethod"));
        createPayload.put("bankAccountId", payload.get("bankAccountId"));
        createPayload.put("payDate", payload.get("payDate"));
        createPayload.put("currency", "CNY");
        createPayload.put("remark", "付款执行 " + r.getReqNo());
        CashDoc doc = create(createPayload);
        doc.setReqId(r.getId());
        doc.setReqNo(r.getReqNo());
        doc.setPayType(str(payload.get("payType")) .isEmpty()
                ? CashDoc.TYPE_PAYMENT : str(payload.get("payType")));
        doc.setDeductAmount(deductSum);
        doc.setActualAmount(amount.subtract(deductSum).setScale(2, RoundingMode.HALF_UP));
        doc.setDeductIds(toJson(deductIds));
        docDao.updateById(doc);

        Map<String, Object> posted = post(doc.getId());

        // 旧 execute 返回形态兼容（paymentDb/ApManagement 测试与前端）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", doc.getId());
        out.put("payNo", doc.getDocNo());
        out.put("voucherNo", posted.get("voucherNo"));
        out.put("actualAmount", nvl(doc.getActualAmount()));
        out.put("writeoffCount", settledCount(doc.getId()));
        FinPaymentRequest after = reqDao.selectById(reqId);
        out.put("requestStatus", after != null ? after.getStatus() : null);
        return out;
    }

    // ================================================================
    // 作废 / 查询
    // ================================================================

    @Override
    @Transactional
    public CashDoc voidDoc(String id, String reason) {
        requireAny("作废收付款单", "ROLE_FINANCE_MGR");
        CashDoc doc = docDao.selectById(id);
        if (doc == null) {
            throw new ServiceException(422, "收付款单不存在");
        }
        if (!CashDoc.ST_PENDING_POST.equals(doc.getStatus())
                && !CashDoc.ST_DRAFT.equals(doc.getStatus())) {
            throw new ServiceException(422, "仅草稿/待过账可作废，已过账请走红字冲销：" + doc.getStatus());
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "作废原因必填");
        }
        doc.setStatus(CashDoc.ST_VOID);
        doc.setRemark(reason);
        docDao.updateById(doc);
        return doc;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size, String direction, String status,
                                          String partyId, String keyword, String dateFrom, String dateTo) {
        LambdaQueryWrapper<CashDoc> qw = new LambdaQueryWrapper<CashDoc>()
                .eq(hasText(direction), CashDoc::getDirection, direction)
                .eq(hasText(status), CashDoc::getStatus, status)
                .eq(hasText(partyId), CashDoc::getPartyId, partyId)
                .like(hasText(keyword), CashDoc::getDocNo, keyword)
                .ge(hasText(dateFrom), CashDoc::getPayDate, dateFrom)
                .le(hasText(dateTo), CashDoc::getPayDate, dateTo)
                .orderByDesc(CashDoc::getPayDate)
                .orderByDesc(CashDoc::getDocNo);
        Page<CashDoc> raw = docDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CashDoc d : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("docNo", d.getDocNo());
            m.put("direction", d.getDirection());
            m.put("partyId", d.getPartyId());
            m.put("partyName", d.getPartyName());
            m.put("amount", d.getAmount());
            m.put("actualAmount", d.getActualAmount());
            m.put("payMethod", d.getPayMethod());
            m.put("payDate", d.getPayDate());
            m.put("status", d.getStatus());
            m.put("writeoffState", d.getWriteoffState());
            m.put("crossPeriod", d.getCrossPeriod());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        CashDoc doc = docDao.selectById(id);
        if (doc == null) {
            throw new ServiceException(422, "收付款单不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", doc);
        m.put("writeoffs", writeoffService.listByDoc(id));
        if (hasText(doc.getApprId())) {
            m.put("apprId", doc.getApprId());
        }
        return m;
    }

    // ================================================================
    // 内部
    // ================================================================

    /**
     * 资金凭证分录（spec 过账资金凭证；借贷零容差由 gl-voucher 保障）。
     * add-fx-revaluation task 3.2：凭证为本位币口径——外币单据按账户记账汇率折算 CNY，
     * 差额/抵扣行吸收舍入残差保证借贷平衡；CNY 账户 rate=1 行为与旧版完全等价。
     */
    private List<GlVoucherService.FinVoucherLineSpec> voucherLines(CashDoc doc, boolean inbound,
                                                                   BigDecimal writeoffTotal,
                                                                   BigDecimal remaining,
                                                                   BigDecimal rate) {
        BigDecimal r = rate == null ? BigDecimal.ONE : rate;
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        BigDecimal cash = cny(nvl(doc.getActualAmount()), r);
        BigDecimal deduct = cny(nvl(doc.getDeductAmount()), r);
        if (inbound) {
            // IN：借 银行存款（实收）/ 贷 应收账款（核销合计）/ 贷 预收账款 2205（差额吸收舍入）
            BigDecimal arCny = cny(writeoffTotal, r);
            if (cash.signum() > 0) {
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_BANK, "DR", cash,
                        "银行存款-" + doc.getAccountName()));
            }
            if (writeoffTotal.signum() > 0) {
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_AR, "CR", arCny,
                        "应收账款-" + doc.getPartyName()));
            }
            BigDecimal pre = cash.subtract(arCny);
            if (pre.signum() > 0) {
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_PRERECEIPT, "CR", pre,
                        "预收账款-" + doc.getPartyName()));
            }
        } else if (CashDoc.TYPE_PREPAY.equals(doc.getPayType())) {
            // OUT 预付：借 预付账款 / 贷 银行存款（prepayment MODIFIED 口径不变）
            BigDecimal amtCny = cny(nvl(doc.getAmount()), r);
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_PREPAY, "DR", amtCny,
                    "预付账款-" + doc.getPartyName()));
            if (cash.signum() > 0) {
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_BANK, "CR", cash,
                        "银行存款-" + doc.getAccountName()));
            }
        } else {
            // OUT 应付：借 应付账款（付款金额）/ 贷 银行存款（实付）/ 贷 采购价差（SCAR 抵扣吸收舍入）
            BigDecimal amtCny = cny(nvl(doc.getAmount()), r);
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_AP, "DR", amtCny,
                    "应付账款-" + doc.getPartyName()));
            if (cash.signum() > 0) {
                lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_BANK, "CR", cash,
                        "银行存款-" + doc.getAccountName()));
            }
            if (deduct.signum() > 0) {
                BigDecimal scar = amtCny.subtract(cash);
                lines.add(GlVoucherService.FinVoucherLineSpec.of("1406", "CR", scar,
                        "SCAR 扣款抵扣冲减采购成本"));
            }
        }
        return lines;
    }

    /** 本位币折算（外币额 × 记账汇率，2 位 HALF_UP；CNY rate=1 恒等） */
    private static BigDecimal cny(BigDecimal fc, BigDecimal rate) {
        return fc.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private void settleDeductions(CashDoc doc) {
        for (String did : strList(doc.getDeductIds())) {
            ScarDeduction d = deductionDao.selectById(did);
            if (d == null) {
                continue;
            }
            d.setStatus("DEDUCTED");
            d.setPaymentId(doc.getId());
            d.setDeductDate(LocalDateTime.now());
            d.setDeductAmount(nvl(d.getAmount()));
            if (deductionDao.updateById(d) == 0) {
                throw new ServiceException(409, "扣款单状态更新冲突：" + d.getDeductNo());
            }
        }
    }

    private void markRequestPaid(FinPaymentRequest r) {
        BigDecimal executed = nvl(docDao.selectExecutedByReq(r.getId()));
        r.setExecutedAmount(executed);
        if (executed.compareTo(nvl(r.getApplyAmount())) >= 0) {
            r.setStatus(FinPaymentRequest.ST_PAID);
        }
        r.setUpdateBy(currentUser());
        if (reqDao.updateById(r) == 0) {
            throw new ServiceException(409, "付款申请状态更新冲突");
        }
    }

    private List<FinApInvoice> loadReqInvoices(FinPaymentRequest r) {
        List<FinApInvoice> out = new ArrayList<>();
        for (String id : strList(r.getInvoiceIds())) {
            FinApInvoice inv = invoiceDao.selectById(id);
            if (inv != null) {
                out.add(inv);
            }
        }
        out.sort(Comparator.comparing(FinApInvoice::getInvoiceDate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private long settledCount(String docId) {
        return woDao.selectCount(new LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff>()
                .eq(com.erp.entity.fin.CashWriteoff::getDocId, docId));
    }

    private String nextDocNo(boolean inbound) {
        String prefix = (inbound ? "RC" : "PY") + LocalDate.now().format(DAY_FMT) + "-";
        int max = 0;
        for (String no : docDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 流水不符跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private String toJson(List<String> v) {
        try {
            return jsonMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> strList(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json,
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String uuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}

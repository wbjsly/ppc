package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPaymentDao;
import com.erp.dao.fin.FinPaymentRequestDao;
import com.erp.dao.fin.FinPaymentWriteoffDao;
import com.erp.dao.qms.ScarDeductionDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPayment;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.entity.fin.FinPaymentWriteoff;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.PaymentService;
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
 * 付款申请与执行实现（spec payment-request + payment-execution，design D3/D4/D5/D6）。
 * 分级审批按金额动态构建；执行内核 = 校验 → 抵扣 → FIFO 核销 → 余额条件扣减 → 付款凭证 → 累计执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> PAY_METHODS =
            List.of("电汇", "支票", "商业汇票", "银行承兑汇票", "现金");

    private final FinPaymentRequestDao reqDao;
    private final FinPaymentDao payDao;
    private final FinPaymentWriteoffDao woDao;
    private final FinApInvoiceDao invoiceDao;
    private final FinBankAccountDao bankDao;
    private final ScarDeductionDao deductionDao;
    private final GlVoucherService voucherService;
    private final SupplierStatementService statementService;
    private final ApprovalEngine approvalEngine;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ================================================================
    // 3.1 申请创建
    // ================================================================

    @Override
    @Transactional
    public FinPaymentRequest create(Map<String, Object> payload) {
        requireRole("创建付款申请", "ROLE_PM");
        String supplierId = str(payload.get("supplierId"));
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        BigDecimal amount = dec(payload.get("applyAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "申请金额必须大于 0");
        }
        List<String> invoiceIds = strList(payload.get("invoiceIds"));
        if (invoiceIds.isEmpty()) {
            throw new ServiceException(422, "至少关联一张已过账发票（FR-4.6-3-7）");
        }
        // 冻结校验（C-4.2-15）与供应商状态
        statementService.assertNotFrozen(supplierId);

        // 发票校验：同供应商、POSTED、未清合计上限
        List<FinApInvoice> invoices = new ArrayList<>();
        BigDecimal unpaid = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (String id : invoiceIds) {
            FinApInvoice inv = invoiceDao.selectById(id);
            if (inv == null) {
                throw new ServiceException(422, "关联发票不存在：" + id);
            }
            if (!supplierId.equals(inv.getSupplierId())) {
                throw new ServiceException(422, "付款申请须按供应商维度发起（发票 " + inv.getInvoiceNo() + " 跨供应商）");
            }
            if (!FinApInvoice.ST_POSTED.equals(inv.getStatus())) {
                throw new ServiceException(422, "仅已过账发票可申请付款：" + inv.getInvoiceNo());
            }
            unpaid = unpaid.add(nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount())));
            invoices.add(inv);
        }
        if (amount.compareTo(unpaid) > 0) {
            throw new ServiceException(422, "申请金额超过所选发票未清应付合计 "
                    + unpaid.toPlainString() + "（FR-4.6-3-7）");
        }

        FinPaymentRequest r = new FinPaymentRequest();
        r.setReqNo(nextNo("PA"));
        r.setSupplierId(supplierId);
        r.setSupplierName(str(payload.get("supplierName")));
        if (!hasText(r.getSupplierName()) && !invoices.isEmpty()) {
            r.setSupplierName(invoices.get(0).getSupplierName());
        }
        r.setApplyAmount(amount.setScale(2, RoundingMode.HALF_UP));
        r.setInvoiceIds(toJson(invoiceIds));
        r.setStatus(FinPaymentRequest.ST_DRAFT);
        r.setExecutedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        r.setRemark(str(payload.get("remark")));
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        reqDao.insert(r);
        return r;
    }

    // ================================================================
    // 3.2 分级审批（FR-4.6-3-8 / BR-4.6-18）
    // ================================================================

    @Override
    @Transactional
    public FinPaymentRequest submit(String id) {
        requireRole("提交付款审批", "ROLE_PM");
        FinPaymentRequest r = require(id);
        if (FinPaymentRequest.ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "审批中，不可重复提交");
        }
        if (!FinPaymentRequest.ST_DRAFT.equals(r.getStatus())
                && !FinPaymentRequest.ST_REJECTED.equals(r.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交审批：" + r.getStatus());
        }
        statementService.assertNotFrozen(r.getSupplierId());
        BigDecimal amount = nvl(r.getApplyAmount());
        List<List<ApprovalNodeSpec>> chain;
        if (amount.compareTo(new BigDecimal("50000")) <= 0) {
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "部门主管审批")));
        } else if (amount.compareTo(new BigDecimal("500000")) <= 0) {
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "部门主管审批")),
                    List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务主管审批")));
        } else {
            chain = List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "部门主管审批")),
                    List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务主管审批")),
                    List.of(ApprovalNodeSpec.sign("ROLE_GM", "总经理审批")));
        }
        var inst = approvalEngine.submit("PaymentRequest", r.getId(),
                "付款申请：" + r.getReqNo() + " / " + str(r.getSupplierName()) + " / "
                        + amount.toPlainString(),
                "ROLE_FINANCE_MGR", chain);
        r.setStatus(FinPaymentRequest.ST_PENDING);
        r.setApprovalId(inst.getId());
        if (reqDao.updateById(r) == 0) {
            throw new ServiceException(409, "付款申请状态更新冲突");
        }
        log.info("payment request {} submitted, 金额 {} 分级节点 {}", r.getReqNo(), amount, chain.size());
        return r;
    }

    @Override
    @Transactional
    public FinPaymentRequest cancel(String id, String reason) {
        requireRole("作废付款申请", "ROLE_PM");
        FinPaymentRequest r = require(id);
        if (FinPaymentRequest.ST_SCHEDULED.equals(r.getStatus())
                || FinPaymentRequest.ST_PAID.equals(r.getStatus())
                || FinPaymentRequest.ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "当前状态不可作废：" + r.getStatus());
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "作废原因必填");
        }
        reqDao.deleteById(r.getId());
        log.info("payment request {} cancelled: {}", r.getReqNo(), reason);
        return r;
    }

    // ================================================================
    // 3.4 排期（FR-4.6-3-9，仅 ADMIN）
    // ================================================================

    @Override
    @Transactional
    public FinPaymentRequest schedule(String id, String planDate) {
        requireRole("付款排期", "ROLE_ADMIN");
        FinPaymentRequest r = require(id);
        if (!FinPaymentRequest.ST_APPROVED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅已审批申请可排期：" + r.getStatus());
        }
        if (!hasText(planDate)) {
            throw new ServiceException(422, "计划付款日必填（FR-4.6-3-9）");
        }
        try {
            r.setPlanDate(LocalDate.parse(planDate));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "计划付款日格式须为 yyyy-MM-dd");
        }
        r.setStatus(FinPaymentRequest.ST_SCHEDULED);
        r.setScheduleBy(SecurityUtils.getCurrentUserId());
        r.setScheduleAt(LocalDateTime.now());
        if (reqDao.updateById(r) == 0) {
            throw new ServiceException(409, "排期状态更新冲突");
        }
        log.info("payment request {} scheduled → {}", r.getReqNo(), planDate);
        return r;
    }

    @Override
    @Transactional
    public FinPaymentRequest setWaitFunds(String id, boolean wait) {
        requireRole("付款排期", "ROLE_ADMIN");
        FinPaymentRequest r = require(id);
        if (wait) {
            if (!FinPaymentRequest.ST_SCHEDULED.equals(r.getStatus())) {
                throw new ServiceException(422, "仅排期中可标记待付款：" + r.getStatus());
            }
            r.setStatus(FinPaymentRequest.ST_WAIT_FUNDS);
        } else {
            if (!FinPaymentRequest.ST_WAIT_FUNDS.equals(r.getStatus())) {
                throw new ServiceException(422, "仅待付款可恢复排期：" + r.getStatus());
            }
            r.setStatus(FinPaymentRequest.ST_SCHEDULED);
        }
        reqDao.updateById(r);
        return r;
    }

    // ================================================================
    // 4.1 / 4.2 执行付款（FR-4.6-3-10/11/12，design D4）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> execute(Map<String, Object> payload) {
        requireRole("执行付款", "ROLE_ADMIN");
        String reqId = str(payload.get("reqId"));
        FinPaymentRequest r = require(reqId);
        if (!FinPaymentRequest.ST_SCHEDULED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅排期中的申请可执行付款（待付款须先恢复排期）：" + r.getStatus());
        }
        BigDecimal amount = dec(payload.get("applyAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "付款金额必须大于 0");
        }
        // L1：累计执行 ≤ 审批（申请）金额
        BigDecimal executed = nvl(payDao.selectExecutedByReq(r.getId()));
        if (executed.add(amount).compareTo(nvl(r.getApplyAmount())) > 0) {
            throw new ServiceException(422, "付款金额超过审批金额（累计已付 "
                    + executed.toPlainString() + " / 审批 " + nvl(r.getApplyAmount()).toPlainString()
                    + "）（FR-4.6-3-10 L1 硬阻断）");
        }
        String payMethod = str(payload.get("payMethod"));
        if (!PAY_METHODS.contains(payMethod)) {
            throw new ServiceException(422, "支付方式仅支持 " + String.join("/", PAY_METHODS) + "（BR-4.6-20）");
        }
        LocalDate payDate;
        try {
            payDate = LocalDate.parse(str(payload.get("payDate")));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "付款日期格式须为 yyyy-MM-dd");
        }
        FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
        if (bank == null) {
            throw new ServiceException(422, "银行账户必填");
        }
        if (!FinBankAccount.ST_ACTIVE.equals(bank.getStatus())) {
            throw new ServiceException(422, "银行账户已停用：" + bank.getAccountName());
        }

        // SCAR 抵扣（design D6）
        List<String> deductIds = strList(payload.get("deductIds"));
        BigDecimal deductSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<ScarDeduction> deductions = new ArrayList<>();
        for (String did : deductIds) {
            ScarDeduction d = deductionDao.selectById(did);
            if (d == null) {
                throw new ServiceException(422, "扣款单不存在：" + did);
            }
            if (!"TO_DEDUCT".equals(d.getStatus())) {
                throw new ServiceException(422, "扣款单状态不可抵扣（须待抵扣，争议/未确认不可）：" + d.getDeductNo()
                        + " " + d.getStatus());
            }
            if (!r.getSupplierId().equals(d.getSupplierId())) {
                throw new ServiceException(422, "扣款单供应商与付款申请不一致：" + d.getDeductNo());
            }
            deductSum = deductSum.add(nvl(d.getAmount()));
            deductions.add(d);
        }
        if (deductSum.compareTo(BigDecimal.ZERO) > 0 && deductSum.compareTo(amount) > 0) {
            throw new ServiceException(422, "抵扣合计 " + deductSum.toPlainString()
                    + " 超过付款金额 " + amount.toPlainString());
        }
        BigDecimal actual = amount.subtract(deductSum).setScale(2, RoundingMode.HALF_UP);

        // FIFO 核销（design D5：所选发票按 invoiceDate 升序，PAID_AMOUNT 条件增量）
        List<FinApInvoice> invoices = loadInvoices(r);
        BigDecimal remaining = amount;
        List<FinPaymentWriteoff> writeoffs = new ArrayList<>();
        for (FinApInvoice inv : invoices) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal open = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
            if (open.signum() <= 0) {
                continue;
            }
            BigDecimal take = remaining.min(open);
            int rows = invoiceDao.update(null, new LambdaUpdateWrapper<FinApInvoice>()
                    .eq(FinApInvoice::getId, inv.getId())
                    .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                    .apply("PAID_AMOUNT + {0} <= TOTAL_AMOUNT", take)
                    .setSql("PAID_AMOUNT = PAID_AMOUNT + " + take.toPlainString()));
            if (rows == 0) {
                throw new ServiceException(422, "发票 " + inv.getInvoiceNo() + " 核销并发冲突或超未清额");
            }
            FinPaymentWriteoff w = new FinPaymentWriteoff();
            w.setKind(FinPaymentWriteoff.KIND_PAYMENT);
            w.setInvoiceId(inv.getId());
            w.setInvoiceNo(inv.getInvoiceNo());
            w.setSupplierId(inv.getSupplierId());
            w.setPoNo(inv.getPoNo());
            w.setAmount(take);
            w.setWriteoffDate(payDate);
            writeoffs.add(w);
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new ServiceException(422, "所选发票未清额不足（剩余待核销 " + remaining.toPlainString() + "）");
        }

        // 余额条件扣减（BR-4.6-21）
        if (actual.signum() > 0) {
            if (bankDao.deductBalance(bank.getId(), actual) == 0) {
                throw new ServiceException(422, "银行账户余额不足：" + bank.getAccountName()
                        + " 当前余额 " + nvl(bank.getBalance()).toPlainString() + "，需实付 " + actual.toPlainString());
            }
        }

        // 付款凭证（FR-4.6-3-11：借 应付账款 / 贷 银行存款；抵扣部分贷 1406，design D6）
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of(AccrualServiceImpl.ACCT_AP, "DR", amount,
                "应付账款（付款 " + r.getReqNo() + "）"));
        if (actual.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1002", "CR", actual,
                    "银行存款-" + bank.getAccountName()));
        }
        if (deductSum.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1406", "CR", deductSum,
                    "SCAR 扣款抵扣冲减采购成本"));
        }
        FinVoucher v = voucherService.create(FinVoucher.TYPE_PAY, payDate,
                "付款单：" + r.getReqNo() + (deductSum.signum() > 0 ? "（含抵扣 " + deductSum.toPlainString() + "）" : ""),
                "PAYMENT", r.getReqNo(), r.getSupplierId(), lines);

        // 付款单 + 核销记录
        FinPayment p = new FinPayment();
        p.setPayNo(nextNo("PY"));
        p.setPayType(FinPayment.TYPE_PAYMENT);
        p.setReqId(r.getId());
        p.setReqNo(r.getReqNo());
        p.setSupplierId(r.getSupplierId());
        p.setSupplierName(r.getSupplierName());
        p.setApplyAmount(amount);
        p.setDeductAmount(deductSum);
        p.setActualAmount(actual);
        p.setPayMethod(payMethod);
        p.setBankAccountId(bank.getId());
        p.setAccountName(bank.getAccountName());
        p.setAccountNo(bank.getAccountNo());
        p.setPayDate(payDate);
        p.setStatus(FinPayment.ST_PAID);
        p.setDeductIds(toJson(deductIds));
        p.setVoucherId(v.getId());
        p.setCreateBy(SecurityUtils.getCurrentUserId());
        payDao.insert(p);
        for (FinPaymentWriteoff w : writeoffs) {
            w.setPaymentId(p.getId());
            w.setCreateBy(SecurityUtils.getCurrentUserId());
            woDao.insert(w);
        }

        // 抵扣单落账（D3）
        for (ScarDeduction d : deductions) {
            d.setStatus("DEDUCTED");
            d.setPaymentId(p.getId());
            d.setDeductDate(LocalDateTime.now());
            d.setDeductAmount(nvl(d.getAmount()));
            if (deductionDao.updateById(d) == 0) {
                throw new ServiceException(409, "扣款单状态更新冲突：" + d.getDeductNo());
            }
        }

        // 申请累计执行 → PAID（累计达审批金额）
        BigDecimal newExecuted = executed.add(amount);
        r.setExecutedAmount(newExecuted);
        if (newExecuted.compareTo(nvl(r.getApplyAmount())) >= 0) {
            r.setStatus(FinPaymentRequest.ST_PAID);
        }
        r.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (reqDao.updateById(r) == 0) {
            throw new ServiceException(409, "付款申请状态更新冲突");
        }

        log.info("payment {} executed: req={} amount={} deduct={} actual={} voucher={} 核销 {} 张",
                p.getPayNo(), r.getReqNo(), amount, deductSum, actual, v.getVoucherNo(), writeoffs.size());
        // 银行对账触发留桩（FR-4.6-3-13，8.7 未建）
        log.info("[TODO-NOTIFY] payment {} 银行对账触发（8.7 财务域桩）", p.getPayNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", p.getId());
        out.put("payNo", p.getPayNo());
        out.put("voucherNo", v.getVoucherNo());
        out.put("actualAmount", actual);
        out.put("writeoffCount", writeoffs.size());
        out.put("requestStatus", r.getStatus());
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String supplierId, String status,
                                          String keyword, String dateFrom, String dateTo) {
        LambdaQueryWrapper<FinPaymentRequest> qw = new LambdaQueryWrapper<FinPaymentRequest>()
                .eq(hasText(supplierId), FinPaymentRequest::getSupplierId, supplierId)
                .eq(hasText(status), FinPaymentRequest::getStatus, status)
                .like(hasText(keyword), FinPaymentRequest::getReqNo, keyword)
                .ge(hasText(dateFrom), FinPaymentRequest::getCreateDate, dateFrom + " 00:00:00")
                .le(hasText(dateTo), FinPaymentRequest::getCreateDate, dateTo + " 23:59:59")
                .orderByDesc(FinPaymentRequest::getCreateDate);
        Page<FinPaymentRequest> raw = reqDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinPaymentRequest r : raw.getRecords()) {
            Map<String, Object> m = row(r);
            if (FinPaymentRequest.ST_PENDING.equals(r.getStatus())) {
                m.put("approvalNodes", approvalNodes(r));
            }
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        FinPaymentRequest r = require(id);
        Map<String, Object> m = row(r);
        List<Map<String, Object>> invoices = new ArrayList<>();
        for (FinApInvoice inv : loadInvoices(r)) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", inv.getId());
            im.put("invoiceNo", inv.getInvoiceNo());
            im.put("invoiceDate", inv.getInvoiceDate());
            im.put("totalAmount", inv.getTotalAmount());
            im.put("paidAmount", inv.getPaidAmount());
            im.put("unpaid", nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount())));
            im.put("status", inv.getStatus());
            invoices.add(im);
        }
        m.put("invoices", invoices);
        List<Map<String, Object>> pays = new ArrayList<>();
        for (FinPayment p : payDao.selectList(new LambdaQueryWrapper<FinPayment>()
                .eq(FinPayment::getReqId, r.getId())
                .orderByAsc(FinPayment::getCreateDate))) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("payNo", p.getPayNo());
            pm.put("applyAmount", p.getApplyAmount());
            pm.put("deductAmount", p.getDeductAmount());
            pm.put("actualAmount", p.getActualAmount());
            pm.put("payMethod", p.getPayMethod());
            pm.put("accountName", p.getAccountName());
            pm.put("payDate", p.getPayDate());
            pm.put("voucherId", p.getVoucherId());
            pays.add(pm);
        }
        m.put("payments", pays);
        m.put("approvalNodes", approvalNodes(r));
        return m;
    }

    @Override
    public List<Map<String, Object>> invoiceCandidates(String supplierId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!hasText(supplierId)) {
            return out;
        }
        for (FinApInvoice inv : invoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getSupplierId, supplierId)
                .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                .orderByAsc(FinApInvoice::getInvoiceDate))) {
            BigDecimal open = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
            if (open.signum() <= 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", inv.getId());
            m.put("invoiceNo", inv.getInvoiceNo());
            m.put("invoiceDate", inv.getInvoiceDate());
            m.put("poNo", inv.getPoNo());
            m.put("totalAmount", inv.getTotalAmount());
            m.put("paidAmount", inv.getPaidAmount());
            m.put("unpaid", open);
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> deductCandidates(String supplierId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!hasText(supplierId)) {
            return out;
        }
        for (ScarDeduction d : deductionDao.selectList(new LambdaQueryWrapper<ScarDeduction>()
                .eq(ScarDeduction::getSupplierId, supplierId)
                .eq(ScarDeduction::getStatus, "TO_DEDUCT")
                .orderByAsc(ScarDeduction::getCreateDate))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("deductNo", d.getDeductNo());
            m.put("scarNo", d.getScarNo());
            m.put("amount", d.getAmount());
            m.put("status", d.getStatus());
            out.add(m);
        }
        return out;
    }

    @Override
    public Page<Map<String, Object>> writeoffPage(long current, long size, String kind,
                                                  String supplierId, String invoiceNo) {
        LambdaQueryWrapper<FinPaymentWriteoff> qw = new LambdaQueryWrapper<FinPaymentWriteoff>()
                .eq(hasText(kind), FinPaymentWriteoff::getKind, kind)
                .eq(hasText(supplierId), FinPaymentWriteoff::getSupplierId, supplierId)
                .like(hasText(invoiceNo), FinPaymentWriteoff::getInvoiceNo, invoiceNo)
                .orderByDesc(FinPaymentWriteoff::getCreateDate);
        Page<FinPaymentWriteoff> raw = woDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinPaymentWriteoff w : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("kind", w.getKind());
            m.put("paymentId", w.getPaymentId());
            m.put("ppId", w.getPpId());
            m.put("invoiceId", w.getInvoiceId());
            m.put("invoiceNo", w.getInvoiceNo());
            m.put("supplierId", w.getSupplierId());
            m.put("poNo", w.getPoNo());
            m.put("amount", w.getAmount());
            m.put("writeoffDate", w.getWriteoffDate());
            m.put("createDate", w.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    // ------------------------------------------------------------------

    /** 申请关联发票（FIFO：invoiceDate 升序） */
    private List<FinApInvoice> loadInvoices(FinPaymentRequest r) {
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

    private List<Map<String, Object>> approvalNodes(FinPaymentRequest r) {
        if (!hasText(r.getApprovalId())) {
            return List.of();
        }
        Map<String, Object> logs = approvalEngine.logs("PaymentRequest", r.getId());
        Object nodes = logs == null ? null : logs.get("tasks");
        if (nodes instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> casted = (List<Map<String, Object>>) list;
            return casted;
        }
        return List.of();
    }

    private FinPaymentRequest require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "付款申请 ID 必填");
        }
        FinPaymentRequest r = reqDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "付款申请不存在");
        }
        return r;
    }

    private String nextNo(String prefix) {
        String p = prefix + LocalDate.now().format(DAY_FMT);
        Integer max = payDaoMax(prefix, p);
        return p + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private Integer payDaoMax(String prefix, String p) {
        if ("PA".equals(prefix)) {
            return reqDao.selectMaxSeq(p);
        }
        return payDao.selectMaxSeq(p);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> userRoles.add(r.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private Map<String, Object> row(FinPaymentRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("reqNo", r.getReqNo());
        m.put("supplierId", r.getSupplierId());
        m.put("supplierName", r.getSupplierName());
        m.put("applyAmount", r.getApplyAmount());
        m.put("invoiceIds", strList(r.getInvoiceIds()));
        m.put("status", r.getStatus());
        m.put("planDate", r.getPlanDate());
        m.put("scheduleBy", r.getScheduleBy());
        m.put("executedAmount", r.getExecutedAmount());
        m.put("approvalId", r.getApprovalId());
        m.put("rejectReason", r.getRejectReason());
        m.put("remark", r.getRemark());
        m.put("createDate", r.getCreateDate());
        return m;
    }

    private String toJson(Object o) {
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object x : list) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
            return out;
        }
        if (o instanceof String s && hasText(s)) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                return m.readValue(s, List.class).stream().map(String::valueOf).toList();
            } catch (Exception e) {
                return List.of(s);
            }
        }
        return List.of();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || "".equals(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }
}

package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPrepaymentDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPrepayment;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.PrepaymentService;
import com.erp.service.fin.SupplierStatementService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预付款实现（spec prepayment，design D5/D8/D9）。
 * 双 L1 校验 → 分级审批 → 预付执行（余额条件扣减 + PPR 凭证）→ 开票后最早未核销顺序冲抵（SETTLE + PPO 凭证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepaymentServiceImpl implements PrepaymentService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> PAY_METHODS =
            List.of("电汇", "支票", "商业汇票", "银行承兑汇票", "现金");

    /** 预付比例默认值（PO.PREPAY_RATIO 为空时，design D8） */
    @Value("${app.proc.prepay-ratio:0.30}")
    private BigDecimal defaultPrepayRatio;

    private final FinPrepaymentDao ppDao;
    private final com.erp.dao.fin.CashDocDao cashDocDao;
    private final com.erp.dao.fin.CashWriteoffDao cashWoDao;
    private final com.erp.service.fin.CashDocService cashDocService;
    private final FinApInvoiceDao invoiceDao;
    private final FinBankAccountDao bankDao;
    private final PurchaseOrderDao poDao;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    private final MdmSupplierDao supplierDao;
    private final GlVoucherService voucherService;
    private final SupplierStatementService statementService;
    private final ApprovalEngine approvalEngine;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ================================================================
    // 5.1 创建（C-4.2-13 双 L1）
    // ================================================================

    @Override
    @Transactional
    public FinPrepayment create(Map<String, Object> payload) {
        requireRole("创建预付款申请", "ROLE_PM");
        String poId = str(payload.get("poId"));
        if (!hasText(poId)) {
            throw new ServiceException(422, "PO 必填");
        }
        PurchaseOrder po = poDao.selectById(poId);
        if (po == null) {
            throw new ServiceException(404, "PO 不存在");
        }
        if (!"APPROVED".equals(po.getStatus())) {
            throw new ServiceException(422, "仅已批准 PO 可申请预付款：" + po.getStatus());
        }
        BigDecimal amount = dec(payload.get("applyAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "预付金额必须大于 0");
        }
        // 冻结（C-4.2-15）与供应商状态
        statementService.assertNotFrozen(po.getSupplierId());
        MdmSupplier sup = supplierDao.selectById(po.getSupplierId());
        if (sup != null && ("FROZEN".equals(sup.getStatus()) || "DISABLED".equals(sup.getStatus()))) {
            throw new ServiceException(422, "供应商状态为 " + sup.getStatus()
                    + "，已冻结付款与预付款申请（预付款清理待办）");
        }

        BigDecimal ratio = po.getPrepayRatio() != null && po.getPrepayRatio().signum() > 0
                ? po.getPrepayRatio() : defaultPrepayRatio;
        // L1-1：超约定预付比例
        BigDecimal total = nvl(po.getTotalAmt());
        if (amount.compareTo(total.multiply(ratio).setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new ServiceException(422, "预付金额超过 PO 约定预付比例 "
                    + ratio.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)
                    + "%（C-4.2-13）：上限 "
                    + total.multiply(ratio).setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        // L1-2：累计预付（含本次）> PO 未清金额
        BigDecimal poOpen = poOpenAmount(po);
        BigDecimal paidBefore = nvl(ppDao.selectPaidByPo(po.getPoNo()));
        if (paidBefore.add(amount).compareTo(poOpen) > 0) {
            throw new ServiceException(422, "累计预付（含本次 " + amount.toPlainString()
                    + "）超过 PO 未清金额 " + poOpen.toPlainString() + "（C-4.2-13）");
        }

        FinPrepayment p = new FinPrepayment();
        p.setPpNo(nextNo());
        p.setPoId(po.getId());
        p.setPoNo(po.getPoNo());
        p.setSupplierId(po.getSupplierId());
        p.setSupplierName(po.getSupplierName());
        p.setPrepayRatio(ratio);
        p.setApplyAmount(amount.setScale(2, RoundingMode.HALF_UP));
        p.setPoTotalAmt(total);
        p.setPoOpenAmt(poOpen);
        p.setStatus(FinPrepayment.ST_DRAFT);
        p.setExecutedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        p.setSettledAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        p.setRemark(str(payload.get("remark")));
        p.setCreateBy(SecurityUtils.getCurrentUserId());
        ppDao.insert(p);
        return p;
    }

    // ================================================================
    // 5.2 分级审批（复用金额分档链）
    // ================================================================

    @Override
    @Transactional
    public FinPrepayment submit(String id) {
        requireRole("提交预付款审批", "ROLE_PM");
        FinPrepayment p = require(id);
        if (FinPrepayment.ST_PENDING.equals(p.getStatus())) {
            throw new ServiceException(422, "审批中，不可重复提交");
        }
        if (!FinPrepayment.ST_DRAFT.equals(p.getStatus()) && !FinPrepayment.ST_REJECTED.equals(p.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交审批：" + p.getStatus());
        }
        BigDecimal amount = nvl(p.getApplyAmount());
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
        var inst = approvalEngine.submit("Prepayment", p.getId(),
                "预付款申请：" + p.getPpNo() + " / " + p.getPoNo() + " / " + amount.toPlainString(),
                "ROLE_FINANCE_MGR", chain);
        p.setStatus(FinPrepayment.ST_PENDING);
        p.setApprovalId(inst.getId());
        if (ppDao.updateById(p) == 0) {
            throw new ServiceException(409, "预付款状态更新冲突");
        }
        log.info("prepayment {} submitted, 金额 {} 节点 {}", p.getPpNo(), amount, chain.size());
        return p;
    }

    @Override
    @Transactional
    public FinPrepayment cancel(String id, String reason) {
        requireRole("作废预付款申请", "ROLE_PM");
        FinPrepayment p = require(id);
        if (FinPrepayment.ST_PENDING.equals(p.getStatus())
                || FinPrepayment.ST_PAID.equals(p.getStatus())) {
            throw new ServiceException(422, "当前状态不可作废：" + p.getStatus());
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "作废原因必填");
        }
        ppDao.deleteById(p.getId());
        log.info("prepayment {} cancelled: {}", p.getPpNo(), reason);
        return p;
    }

    // ================================================================
    // 5.3 预付执行
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> execute(Map<String, Object> payload) {
        requireRole("执行预付款", "ROLE_ADMIN");
        FinPrepayment p = require(str(payload.get("ppId")));
        if (!FinPrepayment.ST_APPROVED.equals(p.getStatus())) {
            throw new ServiceException(422, "仅已审批预付款可执行：" + p.getStatus());
        }
        String payMethod = str(payload.get("payMethod"));
        if (!PAY_METHODS.contains(payMethod)) {
            throw new ServiceException(422, "支付方式仅支持 " + String.join("/", PAY_METHODS));
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
        BigDecimal amount = nvl(p.getApplyAmount());

        // add-payment-collection：预付执行走统一收付款中心（OUT + PREPAY，spec prepayment MODIFIED）
        // 过账内核完成余额条件扣减 + PPR 凭证（借 1123 / 贷 1002，口径不变）
        Map<String, Object> createPayload = new LinkedHashMap<>();
        createPayload.put("direction", com.erp.entity.fin.CashDoc.DIR_OUT);
        createPayload.put("partyId", p.getSupplierId());
        createPayload.put("partyName", p.getSupplierName());
        createPayload.put("amount", amount);
        createPayload.put("payMethod", payMethod);
        createPayload.put("bankAccountId", bank.getId());
        createPayload.put("payDate", payDate.toString());
        createPayload.put("currency", "CNY");
        createPayload.put("remark", "预付执行 " + p.getPpNo());
        com.erp.entity.fin.CashDoc doc = cashDocService.create(createPayload);
        doc.setReqId(p.getId());
        doc.setReqNo(p.getPpNo());
        doc.setPayType(com.erp.entity.fin.CashDoc.TYPE_PREPAY);
        cashDocDao.updateById(doc);
        Map<String, Object> posted = cashDocService.post(doc.getId());

        p.setStatus(FinPrepayment.ST_PAID);
        p.setExecutedAmount(amount);
        p.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (ppDao.updateById(p) == 0) {
            throw new ServiceException(409, "预付款状态更新冲突");
        }
        log.info("prepayment {} executed via cash doc {}: {} voucher={}",
                p.getPpNo(), doc.getDocNo(), amount, posted.get("voucherNo"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", doc.getId());
        out.put("payNo", doc.getDocNo());
        out.put("voucherNo", posted.get("voucherNo"));
        out.put("actualAmount", amount);
        return out;
    }

    // ================================================================
    // 5.4 到货开票后冲抵应付（BR-4.2-52）
    // ================================================================

    @Override
    @Transactional
    public int settleForPo(String poNo) {
        if (!hasText(poNo)) {
            return 0;
        }
        // 预付单：PAID 且仍有未核销余额，按日期升序（最早未核销顺序）
        List<FinPrepayment> pps = new ArrayList<>();
        for (FinPrepayment p : ppDao.selectList(new LambdaQueryWrapper<FinPrepayment>()
                .eq(FinPrepayment::getPoNo, poNo)
                .eq(FinPrepayment::getStatus, FinPrepayment.ST_PAID)
                .orderByAsc(FinPrepayment::getCreateDate))) {
            BigDecimal rem = nvl(p.getExecutedAmount()).subtract(nvl(p.getSettledAmount()));
            if (rem.signum() > 0) {
                pps.add(p);
            }
        }
        if (pps.isEmpty()) {
            return 0;
        }
        // 未清发票：POSTED 且有余额，按发票日期升序（FIFO）
        List<FinApInvoice> invs = new ArrayList<>();
        for (FinApInvoice inv : invoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getPoNo, poNo)
                .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                .orderByAsc(FinApInvoice::getInvoiceDate))) {
            if (nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount())).signum() > 0) {
                invs.add(inv);
            }
        }
        if (invs.isEmpty()) {
            return 0;
        }

        int count = 0;
        BigDecimal settleTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinPrepayment p : pps) {
            BigDecimal ppRem = nvl(p.getExecutedAmount()).subtract(nvl(p.getSettledAmount()));
            for (FinApInvoice inv : invs) {
                if (ppRem.signum() <= 0) {
                    break;
                }
                BigDecimal invRem = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
                if (invRem.signum() <= 0) {
                    continue;
                }
                // 幂等：该预付单与该发票已有冲抵记录 → 跳过
                Long exists = cashWoDao.selectCount(
                        new LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff>()
                        .eq(com.erp.entity.fin.CashWriteoff::getWriteoffMode,
                                com.erp.entity.fin.CashWriteoff.MODE_SETTLE)
                        .eq(com.erp.entity.fin.CashWriteoff::getDocId, p.getId())
                        .eq(com.erp.entity.fin.CashWriteoff::getTargetId, inv.getId()));
                if (exists != null && exists > 0) {
                    continue;
                }
                BigDecimal take = ppRem.min(invRem);
                int rows = invoiceDao.update(null, new LambdaUpdateWrapper<FinApInvoice>()
                        .eq(FinApInvoice::getId, inv.getId())
                        .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                        .apply("PAID_AMOUNT + {0} <= TOTAL_AMOUNT", take)
                        .setSql("PAID_AMOUNT = PAID_AMOUNT + " + take.toPlainString()));
                if (rows == 0) {
                    throw new ServiceException(422, "发票 " + inv.getInvoiceNo() + " 冲抵并发冲突");
                }
                com.erp.entity.fin.CashWriteoff w = new com.erp.entity.fin.CashWriteoff();
                String wid = java.util.UUID.randomUUID().toString().replace("-", "");
                w.setId(wid);
                w.setWoNo("PPO" + wid.substring(0, 12).toUpperCase());
                w.setDocId(p.getId()); // SETTLE 行以预付单为锚（cash_writeoff.DOC_ID 语义扩展）
                w.setTargetType(com.erp.entity.fin.CashWriteoff.TARGET_AP);
                w.setTargetId(inv.getId());
                w.setTargetNo(inv.getInvoiceNo());
                w.setAmount(take);
                w.setWriteoffMode(com.erp.entity.fin.CashWriteoff.MODE_SETTLE);
                w.setWriteoffDate(LocalDate.now());
                w.setRemark("预付冲抵 " + poNo);
                w.setCreateBy(SecurityUtils.getCurrentUserId());
                cashWoDao.insert(w);

                p.setSettledAmount(nvl(p.getSettledAmount()).add(take));
                ppDao.updateById(p);
                ppRem = ppRem.subtract(take);
                settleTotal = settleTotal.add(take);
                count++;
                log.info("prepayment {} 冲抵发票 {} 金额 {}（BR-4.2-52）",
                        p.getPpNo(), inv.getInvoiceNo(), take);
            }
        }
        if (settleTotal.signum() > 0) {
            // 冲抵凭证：借 应付账款 / 贷 预付账款
            voucherService.create(FinVoucher.TYPE_PREPAY_OFFSET, LocalDate.now(),
                    "预付冲抵应付：" + poNo + "（合计 " + settleTotal.toPlainString() + "）",
                    "SETTLE", poNo, null,
                    List.of(GlVoucherService.FinVoucherLineSpec.of(AccrualServiceImpl.ACCT_AP, "DR",
                            settleTotal, "应付账款（预付冲抵 " + poNo + "）"),
                            GlVoucherService.FinVoucherLineSpec.of("1123", "CR", settleTotal,
                                    "预付账款（最早未核销顺序冲抵）")));
        }
        return count;
    }

    // ================================================================
    // 5.6 清理待办
    // ================================================================

    @Override
    public List<Map<String, Object>> cleanupTodos() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinPrepayment p : ppDao.selectList(new LambdaQueryWrapper<FinPrepayment>()
                .in(FinPrepayment::getStatus, FinPrepayment.ST_PAID, FinPrepayment.ST_APPROVED)
                .orderByAsc(FinPrepayment::getCreateDate))) {
            BigDecimal rem = nvl(p.getExecutedAmount()).subtract(nvl(p.getSettledAmount()));
            if (rem.signum() <= 0) {
                continue;
            }
            MdmSupplier sup = supplierDao.selectById(p.getSupplierId());
            String st = sup == null ? null : sup.getStatus();
            if ("FROZEN".equals(st) || "DISABLED".equals(st)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ppNo", p.getPpNo());
                m.put("poNo", p.getPoNo());
                m.put("supplierName", p.getSupplierName());
                m.put("supplierStatus", st);
                m.put("unsettled", rem);
                m.put("reason", "供应商状态 " + st + "，存在已付未核销预付款（预付款清理待办）");
                out.add(m);
            }
        }
        // 「逾期未交付超 14 天」维度留桩（交期数据未接，design D9）
        if (!out.isEmpty()) {
            log.info("[TODO-NOTIFY] 预付款清理待办 {} 条（逾期未交付 14 天判定留桩）", out.size());
        }
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String supplierId, String poNo,
                                          String status) {
        LambdaQueryWrapper<FinPrepayment> qw = new LambdaQueryWrapper<FinPrepayment>()
                .eq(hasText(supplierId), FinPrepayment::getSupplierId, supplierId)
                .like(hasText(poNo), FinPrepayment::getPoNo, poNo)
                .eq(hasText(status), FinPrepayment::getStatus, status)
                .orderByDesc(FinPrepayment::getCreateDate);
        Page<FinPrepayment> raw = ppDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinPrepayment p : raw.getRecords()) {
            rows.add(row(p));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        FinPrepayment p = require(id);
        Map<String, Object> m = row(p);
        List<Map<String, Object>> settles = new ArrayList<>();
        for (com.erp.entity.fin.CashWriteoff w : cashWoDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff>()
                .eq(com.erp.entity.fin.CashWriteoff::getWriteoffMode,
                        com.erp.entity.fin.CashWriteoff.MODE_SETTLE)
                .eq(com.erp.entity.fin.CashWriteoff::getDocId, p.getId())
                .orderByAsc(com.erp.entity.fin.CashWriteoff::getCreateDate))) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("invoiceNo", w.getTargetNo());
            wm.put("amount", w.getAmount());
            wm.put("writeoffDate", w.getWriteoffDate());
            settles.add(wm);
        }
        m.put("settles", settles);
        return m;
    }

    // ------------------------------------------------------------------

    /** PO 未清金额 = 总额 − 该 PO 已过账入库金额（C-4.2-13） */
    private BigDecimal poOpenAmount(PurchaseOrder po) {
        BigDecimal posted = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<GoodsReceipt> grs = grDao.selectList(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoNo, po.getPoNo())
                .eq(GoodsReceipt::getStatus, "POSTED"));
        for (GoodsReceipt gr : grs) {
            for (GoodsReceiptLine l : grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                    .eq(GoodsReceiptLine::getGrId, gr.getId())
                    .eq(GoodsReceiptLine::getStatus, "POSTED"))) {
                posted = posted.add(nvl(l.getAmount()));
            }
        }
        BigDecimal open = nvl(po.getTotalAmt()).subtract(posted);
        return open.signum() < 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : open;
    }

    private FinPrepayment require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "预付款 ID 必填");
        }
        FinPrepayment p = ppDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "预付款不存在");
        }
        return p;
    }

    private String nextNo() {
        String p = "PP" + LocalDate.now().format(DAY_FMT);
        Integer max = ppDao.selectMaxSeq(p);
        return p + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private Map<String, Object> row(FinPrepayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("ppNo", p.getPpNo());
        m.put("poId", p.getPoId());
        m.put("poNo", p.getPoNo());
        m.put("supplierId", p.getSupplierId());
        m.put("supplierName", p.getSupplierName());
        m.put("prepayRatio", p.getPrepayRatio());
        m.put("applyAmount", p.getApplyAmount());
        m.put("poTotalAmt", p.getPoTotalAmt());
        m.put("poOpenAmt", p.getPoOpenAmt());
        m.put("status", p.getStatus());
        m.put("executedAmount", p.getExecutedAmount());
        m.put("settledAmount", p.getSettledAmount());
        m.put("unsettled", nvl(p.getExecutedAmount()).subtract(nvl(p.getSettledAmount())));
        m.put("approvalId", p.getApprovalId());
        m.put("rejectReason", p.getRejectReason());
        m.put("remark", p.getRemark());
        m.put("createDate", p.getCreateDate());
        return m;
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

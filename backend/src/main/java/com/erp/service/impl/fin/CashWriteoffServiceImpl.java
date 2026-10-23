package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.CashWriteoffDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.CashWriteoff;
import com.erp.entity.fin.FinApInvoice;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.CashWriteoffService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一核销内核实现（spec payment-collection，design D4）：
 * FIFO（IN→AR 池 / OUT→所选发票或供应商池）→ 币种断言 C-4.6-11 → 跨期判定 C-4.6-02 →
 * cash_writeoff 落账；跨期挂审批，批准后回写发票 PAID（applyApprovedSettle）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashWriteoffServiceImpl implements CashWriteoffService {

    /** 跨期核销审批 BIZ_TYPE（spec payment-collection 跨期核销审批与留痕） */
    public static final String BIZ_CROSS = "CashWriteoffCross";

    private final CashDocDao docDao;
    private final CashWriteoffDao woDao;
    private final ArInvoiceDao arDao;
    private final FinApInvoiceDao invoiceDao;

    /** @Lazy 解环：ApprovalEngine → ApprovalCallback 列表 → 本服务（change add-payment-collection） */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ApprovalEngine approvalEngine;


    // ================================================================
    // 过账自动核销
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> settle(CashDoc doc, List<?> targets) {
        boolean inbound = CashDoc.DIR_IN.equals(doc.getDirection());
        String targetType = inbound ? CashWriteoff.TARGET_AR : CashWriteoff.TARGET_AP;

        // FIFO 池：IN → 客户未清应收；OUT → 所选发票（付款申请）或供应商未清应付
        List<FinApInvoice> apTargets = new ArrayList<>();
        List<ArInvoice> arTargets = new ArrayList<>();
        BigDecimal remaining = nvl(doc.getAmount());
        String docPeriod = periodOf(doc.getPayDate());
        boolean crossPeriod = false;
        BigDecimal writeoffTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<CashWriteoff> rows = new ArrayList<>();

        if (inbound) {
            List<ArInvoice> pool = arDao.selectFifoPool(doc.getPartyId());
            for (ArInvoice ar : pool) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal open = arOpen(ar);
                if (open.signum() <= 0) {
                    continue;
                }
                assertCurrency(doc.getCurrency(), null, ar.getArNo()); // AR 单据无币种列，默认 CNY
                BigDecimal take = remaining.min(open).setScale(2, RoundingMode.HALF_UP);
                if (!docPeriod.equals(periodOf(ar.getInvoiceDate()))) {
                    crossPeriod = true;
                }
                CashWriteoff row = arRow(doc, targetType, ar, take);
                row.setCreateBy(currentUser());
                woDao.insert(row); // 逐行落库：nextWoNo() 依赖已插入行推号（唯一键防重）
                rows.add(row);
                writeoffTotal = writeoffTotal.add(take);
                remaining = remaining.subtract(take);
            }
        } else {
            // OUT：所选发票优先（付款申请口径），无选择时按供应商 FIFO 池
            if (targets != null && !targets.isEmpty()) {
                for (Object t : targets) {
                    apTargets.add((FinApInvoice) t);
                }
                apTargets.sort(Comparator.comparing(FinApInvoice::getInvoiceDate,
                        Comparator.nullsLast(Comparator.naturalOrder())));
            } else {
                apTargets.addAll(invoiceDao.selectFifoPool(doc.getPartyId()));
            }
            for (FinApInvoice inv : apTargets) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal open = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
                if (open.signum() <= 0) {
                    continue;
                }
                assertCurrency(doc.getCurrency(), inv.getCurrency(), inv.getInvoiceNo());
                BigDecimal take = remaining.min(open).setScale(2, RoundingMode.HALF_UP);
                if (!docPeriod.equals(periodOf(inv.getInvoiceDate()))) {
                    crossPeriod = true;
                }
                CashWriteoff row = apRow(doc, targetType, inv, take);
                row.setCreateBy(currentUser());
                woDao.insert(row); // 逐行落库防编号冲突（同上）
                rows.add(row);
                writeoffTotal = writeoffTotal.add(take);
                remaining = remaining.subtract(take);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writeoffTotal", writeoffTotal);
        out.put("remaining", remaining);
        out.put("crossPeriod", crossPeriod);
        out.put("rowCount", rows.size());
        return out;
    }

    // ================================================================
    // 跨期审批后执行 / 驳回取消
    // ================================================================

    @Override
    @Transactional
    public void applyApprovedSettle(String docId) {
        CashDoc doc = docDao.selectById(docId);
        if (doc == null) {
            log.warn("cash doc approved but missing: {}", docId);
            return;
        }
        if (CashDoc.WO_DONE.equals(doc.getWriteoffState())) {
            return; // 幂等
        }
        for (CashWriteoff w : woDao.selectList(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getDocId, docId)
                .eq(CashWriteoff::getDelFlag, "0"))) {
            applyPaired(w);
        }
        markState(doc, CashDoc.WO_DONE, null);
        log.info("cash doc {} cross-period writeoff approved & applied ({} rows)",
                doc.getDocNo(), woDao.selectCount(new LambdaQueryWrapper<CashWriteoff>()
                        .eq(CashWriteoff::getDocId, docId)));
    }

    /** 驳回取消：删除挂起核销行，未清额不变（spec 跨期核销审批与留痕） */
    @Transactional
    public void cancelPendingSettle(String docId, String reason) {
        CashDoc doc = docDao.selectById(docId);
        if (doc == null || CashDoc.WO_DONE.equals(doc.getWriteoffState())) {
            return;
        }
        woDao.delete(new LambdaQueryWrapper<CashWriteoff>().eq(CashWriteoff::getDocId, docId));
        markState(doc, CashDoc.WO_REJECTED, reason);
        log.info("cash doc {} cross-period writeoff rejected: {}", doc.getDocNo(), reason);
    }

    // ================================================================
    // 人工核销
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> manualSettle(String docId, String targetType, String targetId,
                                            BigDecimal amount) {
        CashDoc doc = docDao.selectById(docId);
        if (doc == null) {
            throw new ServiceException(422, "收付款单不存在");
        }
        if (CashDoc.ST_VOID.equals(doc.getStatus())) {
            throw new ServiceException(422, "已作废单据不可核销");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "核销金额必须大于 0");
        }
        String docPeriod = periodOf(doc.getPayDate());
        CashWriteoff row;
        if (CashWriteoff.TARGET_AR.equals(targetType)) {
            ArInvoice ar = arDao.selectById(targetId);
            if (ar == null) {
                throw new ServiceException(422, "应收单不存在");
            }
            if (!doc.getPartyId().equals(ar.getCustomerId())) {
                throw new ServiceException(422, "核销客户与收款单客户不一致，禁止交叉核销");
            }
            assertCurrency(doc.getCurrency(), null, ar.getArNo()); // AR 单据无币种列，默认 CNY
            BigDecimal open = arOpen(ar);
            if (amount.compareTo(open) > 0) {
                throw new ServiceException(422, "核销金额超出应收未清额 " + strip(open));
            }
            row = arRow(doc, targetType, ar, amount.setScale(2, RoundingMode.HALF_UP));
        } else if (CashWriteoff.TARGET_AP.equals(targetType)) {
            FinApInvoice inv = invoiceDao.selectById(targetId);
            if (inv == null) {
                throw new ServiceException(422, "应付发票不存在");
            }
            if (!doc.getPartyId().equals(inv.getSupplierId())) {
                throw new ServiceException(422, "核销供应商与付款单供应商不一致，禁止交叉核销");
            }
            assertCurrency(doc.getCurrency(), inv.getCurrency(), inv.getInvoiceNo());
            BigDecimal open = nvl(inv.getTotalAmount()).subtract(nvl(inv.getPaidAmount()));
            if (amount.compareTo(open) > 0) {
                throw new ServiceException(422, "核销金额超出应付未清额 " + strip(open));
            }
            row = apRow(doc, targetType, inv, amount.setScale(2, RoundingMode.HALF_UP));
        } else {
            throw new ServiceException(422, "核销目标类型仅支持 AR/AP");
        }

        boolean cross = !docPeriod.equals(periodOf(CashWriteoff.TARGET_AR.equals(targetType)
                ? arDao.selectById(targetId).getInvoiceDate()
                : invoiceDao.selectById(targetId).getInvoiceDate()));
        row.setWriteoffMode(CashWriteoff.MODE_MANUAL);
        row.setCreateBy(currentUser());
        woDao.insert(row);

        if (cross) {
            // 跨期人工核销：同样挂审批（spec C-4.6-02）
            doc.setCrossPeriod("1");
            doc.setWriteoffState(CashDoc.WO_PENDING);
            docDao.updateById(doc);
            submitCrossApproval(doc);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("writeoffId", row.getId());
            out.put("crossPeriod", true);
            out.put("message", "跨期核销已提交财务主管审批（C-4.6-02），批准后生效");
            return out;
        }
        applyPaired(row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writeoffId", row.getId());
        out.put("crossPeriod", false);
        return out;
    }

    @Override
    public List<Map<String, Object>> listByDoc(String docId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CashWriteoff w : woDao.selectList(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getDocId, docId)
                .eq(CashWriteoff::getDelFlag, "0")
                .orderByAsc(CashWriteoff::getWriteoffDate))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("woNo", w.getWoNo());
            m.put("targetType", w.getTargetType());
            m.put("targetNo", w.getTargetNo());
            m.put("amount", w.getAmount());
            m.put("writeoffMode", w.getWriteoffMode());
            m.put("writeoffDate", w.getWriteoffDate());
            out.add(m);
        }
        return out;
    }

    // ================================================================
    // 内部
    // ================================================================

    /** 核销行回写发票 PAID（AR/AP 各自口径；条件更新防并发超核销） */
    private void applyPaired(CashWriteoff w) {
        if (CashWriteoff.TARGET_AR.equals(w.getTargetType())) {
            int rows = arDao.update(null, new LambdaUpdateWrapper<ArInvoice>()
                    .eq(ArInvoice::getId, w.getTargetId())
                    .in(ArInvoice::getStatus, ArInvoice.ST_UNPAID, ArInvoice.ST_PARTIAL)
                    .apply("PAID_AMOUNT + {0} <= AMOUNT - RED_AMOUNT", w.getAmount())
                    .setSql("PAID_AMOUNT = PAID_AMOUNT + " + w.getAmount().toPlainString()));
            if (rows == 0) {
                throw new ServiceException(422, "应收 " + w.getTargetNo() + " 核销并发冲突或超未清额");
            }
            refreshArStatus(w.getTargetId());
        } else {
            int rows = invoiceDao.update(null, new LambdaUpdateWrapper<FinApInvoice>()
                    .eq(FinApInvoice::getId, w.getTargetId())
                    .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                    .apply("PAID_AMOUNT + {0} <= TOTAL_AMOUNT", w.getAmount())
                    .setSql("PAID_AMOUNT = PAID_AMOUNT + " + w.getAmount().toPlainString()));
            if (rows == 0) {
                throw new ServiceException(422, "发票 " + w.getTargetNo() + " 核销并发冲突或超未清额");
            }
        }
    }

    private void refreshArStatus(String arId) {
        ArInvoice ar = arDao.selectById(arId);
        if (ar == null) {
            return;
        }
        BigDecimal open = arOpen(ar);
        ar.setStatus(open.signum() <= 0 ? ArInvoice.ST_PAID
                : (nvl(ar.getPaidAmount()).signum() > 0 ? ArInvoice.ST_PARTIAL : ArInvoice.ST_UNPAID));
        arDao.updateById(ar);
    }

    private CashWriteoff arRow(CashDoc doc, String targetType, ArInvoice ar, BigDecimal take) {
        CashWriteoff w = baseRow(doc, targetType, ar.getId(), ar.getArNo(), take);
        w.setCustomerId(ar.getCustomerId());
        w.setDueDate(ar.getDueDate());
        w.setOnTime(ar.getDueDate() != null && !doc.getPayDate().isAfter(ar.getDueDate()) ? "1" : "0");
        w.setWriteType("RECEIPT");
        return w;
    }

    private CashWriteoff apRow(CashDoc doc, String targetType, FinApInvoice inv, BigDecimal take) {
        CashWriteoff w = baseRow(doc, targetType, inv.getId(), inv.getInvoiceNo(), take);
        w.setWriteType("PAYMENT");
        return w;
    }

    private CashWriteoff baseRow(CashDoc doc, String targetType, String targetId,
                                 String targetNo, BigDecimal take) {
        CashWriteoff w = new CashWriteoff();
        w.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        w.setWoNo(nextWoNo());
        w.setDocId(doc.getId());
        w.setDocNo(doc.getDocNo());
        w.setTargetType(targetType);
        w.setTargetId(targetId);
        w.setTargetNo(targetNo);
        w.setAmount(take);
        w.setWriteoffMode(CashWriteoff.MODE_AUTO);
        w.setWriteoffDate(doc.getPayDate());
        return w;
    }

    /** 跨期核销审批提交（财务主管单节点，spec C-4.6-02 L5 留痕） */
    public void submitCrossApproval(CashDoc doc) {
        var inst = approvalEngine.submit(BIZ_CROSS, doc.getId(),
                "跨期核销：" + doc.getDocNo() + " / " + str(doc.getPartyName()) + " / "
                        + nvl(doc.getAmount()).toPlainString(),
                "ROLE_FINANCE_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "跨期核销审批"))));
        doc.setApprId(inst.getId());
        if (docDao.updateById(doc) == 0) {
            throw new ServiceException(409, "跨期核销审批锚更新冲突");
        }
    }

    private void markState(CashDoc doc, String state, String reason) {
        doc.setWriteoffState(state);
        if (reason != null) {
            doc.setRejectReason(reason);
        }
        docDao.updateById(doc);
    }

    /** 币种一致断言（C-4.6-11，L1 一票否决；发票币种空 = CNY） */
    private void assertCurrency(String docCurrency, String invoiceCurrency, String invoiceNo) {
        String docCur = docCurrency == null || docCurrency.isBlank() ? "CNY" : docCurrency;
        String invCur = invoiceCurrency == null || invoiceCurrency.isBlank() ? "CNY" : invoiceCurrency;
        if (!docCur.equalsIgnoreCase(invCur)) {
            throw new ServiceException(422, "核销币种与原单据币种不一致（C-4.6-11）：单据 "
                    + docCur + " vs " + invoiceNo + " " + invCur + "，请先汇兑重估或同币种操作");
        }
    }

    private String nextWoNo() {
        String prefix = "CWO" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : woDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 流水格式不符跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private static String periodOf(LocalDate d) {
        return d == null ? "" : d.toString().substring(0, 7).replace("-", "");
    }

    /** AR 未清 = AMOUNT − RED − PAID（ArInvoice 口径） */
    private static BigDecimal arOpen(ArInvoice ar) {
        return nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount())).subtract(nvl(ar.getPaidAmount()));
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}

package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.InvoiceDeliveryDao;
import com.erp.dao.fin.InvoiceVoidApplyDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.entity.fin.InvoiceVoidApply;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.FinPeriodService;
import com.erp.service.fin.RedVoidService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发票作废实现（M9-4-2，spec red-invoice-void，design D4）。
 *
 * 三条件系内事实代理（BR-4.14-24，任一不满足 422 一次性全量返回并引导红冲）：
 *  ①开具当月：invoiceDate 在当前税期所属月内；
 *  ②未交付购方：交付台账无该发票 SENT 记录（PENDING/FAILED 视为未送达）；
 *  ③购方未认证：无客户确认记录（CONFIRMED，代理指标——页面注明口径）。
 * 已 VOID 发票不可再作废；作废后发票不可再红冲（BR-4.14-28 联动由红冲侧 VOID 校验承担）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedVoidServiceImpl implements RedVoidService {

    public static final String BIZ_TYPE = "RedInvoiceVoid";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final InvoiceVoidApplyDao voidDao;
    private final SalesInvoiceDao invoiceDao;
    private final InvoiceDeliveryDao deliveryDao;
    private final ApprovalEngine approvalEngine;
    private final FinPeriodService finPeriodService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Map<String, Object> payload) {
        String invoiceId = str(payload.get("invoiceId"));
        String reason = str(payload.get("reason"));
        if (!hasText(invoiceId) || !hasText(reason)) {
            throw new ServiceException(422, "作废申请要素必填（发票/原因）");
        }
        SalesInvoice inv = invoiceDao.selectById(invoiceId.trim());
        if (inv == null) {
            throw new ServiceException(404, "发票不存在：" + invoiceId);
        }
        if (SalesInvoice.ST_VOID.equals(inv.getStatus())) {
            throw new ServiceException(422, "发票已作废，不可重复作废");
        }
        if (SalesInvoice.ST_RED_FULL.equals(inv.getStatus())) {
            throw new ServiceException(422, "发票已全额红冲，不可作废");
        }

        // 三条件逐项校验（BR-4.14-24）：一次性返回全部未满足项
        List<String> unmet = new ArrayList<>();
        String currentPeriod = currentPeriod();
        String invoicePeriod = inv.getInvoiceDate() == null ? null
                : inv.getInvoiceDate().format(PERIOD_FMT);
        if (invoicePeriod == null || !invoicePeriod.equals(currentPeriod)) {
            unmet.add("①开具当月：发票开具于 " + nvl(invoicePeriod) + "，非当前税期 "
                    + currentPeriod + "（已跨月）");
        }
        long sent = deliveryDao.selectCount(new LambdaQueryWrapper<InvoiceDelivery>()
                .eq(InvoiceDelivery::getInvoiceNo, inv.getInvoiceNo())
                .eq(InvoiceDelivery::getStatus, InvoiceDelivery.STATUS_SENT)
                .eq(InvoiceDelivery::getDelFlag, "0"));
        if (sent > 0) {
            unmet.add("②未交付购方：该发票存在已发送交付记录（SENT），已交付购方");
        }
        boolean confirmed = SalesInvoice.ST_CONFIRMED.equals(inv.getStatus())
                || inv.getConfirmAt() != null;
        if (confirmed) {
            unmet.add("③购方未认证：客户已确认该发票（视为已认证抵扣）");
        }
        if (!unmet.isEmpty()) {
            throw new ServiceException(422, "不满足作废条件，不可作废，请发起红冲流程（BR-4.14-24）："
                    + String.join("；", unmet));
        }

        // 建单 + 提交审批（C-4.14-03 留痕；级别参照红冲分级）
        InvoiceVoidApply a = new InvoiceVoidApply();
        a.setId(uuid());
        a.setApplyNo(nextNo("VSAP", voidDao::selectNosByPrefix));
        a.setInvoiceId(inv.getId());
        a.setInvoiceNo(inv.getInvoiceNo());
        a.setCustomerId(inv.getCustomerId());
        a.setCustomerCode(inv.getCustomerCode());
        a.setCustomerName(inv.getCustomerName());
        a.setAmount(inv.getAmount());
        a.setReason(reason.trim());
        a.setStatus(InvoiceVoidApply.ST_DRAFT);
        a.setCreateBy(currentUser());
        voidDao.insert(a);

        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "发票作废审批")));
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE, a.getId(),
                "发票作废 " + a.getApplyNo() + "（" + inv.getInvoiceNo() + "）"
                        + nvl(inv.getCustomerName()), "ROLE_FIN_MGR", chain);
        a.setApprId(inst.getId());
        voidDao.updateById(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("applyNo", a.getApplyNo());
        out.put("invoiceNo", inv.getInvoiceNo());
        out.put("apprId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        log.info("void apply {} for invoice {} submitted", a.getApplyNo(), inv.getInvoiceNo());
        return out;
    }

    @Override
    public Page<InvoiceVoidApply> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<InvoiceVoidApply> qw = new LambdaQueryWrapper<InvoiceVoidApply>()
                .eq(InvoiceVoidApply::getDelFlag, "0")
                .eq(hasText(status), InvoiceVoidApply::getStatus, status)
                .and(hasText(keyword), w -> w.like(InvoiceVoidApply::getApplyNo, keyword)
                        .or().like(InvoiceVoidApply::getInvoiceNo, keyword)
                        .or().like(InvoiceVoidApply::getCustomerName, keyword))
                .orderByDesc(InvoiceVoidApply::getCreateDate);
        return voidDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String applyId) {
        InvoiceVoidApply a = voidDao.selectById(applyId);
        if (a == null) {
            throw new ServiceException(404, "作废申请单不存在：" + applyId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        out.put("invoice", invoiceDao.selectById(a.getInvoiceId()));
        if (hasText(a.getApprId())) {
            out.put("approval", approvalEngine.logs(BIZ_TYPE, a.getId()));
        }
        return out;
    }

    /** 审批通过 → 执行作废逻辑内聚于 RedVoidApprovalCallback（避免 ApprovalEngine 循环依赖） */

    private String currentPeriod() {
        Object p = finPeriodService.current().get("period");
        return p == null ? LocalDate.now().format(PERIOD_FMT) : String.valueOf(p);
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

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinPaymentRequest;

import java.util.List;
import java.util.Map;

/**
 * 付款申请与执行（2.7.3，spec payment-request + payment-execution）。
 * 申请 → 分级审批（PaymentApprovalCallback）→ 排期（仅 ADMIN）→ 执行（余额校验/超审批金额 L1/
 * 付款凭证/FIFO 核销/SCAR 抵扣/银行对账留桩）。
 */
public interface PaymentService {

    /** 创建付款申请（供应商 + 发票集合 + 金额；金额≤未清合计、同供应商、冻结校验） */
    FinPaymentRequest create(Map<String, Object> payload);

    /** 提交分级审批（金额分档动态链） */
    FinPaymentRequest submit(String id);

    /** 作废（仅 DRAFT/REJECTED，原因必填） */
    FinPaymentRequest cancel(String id, String reason);

    /** 排期（APPROVED → SCHEDULED，计划付款日必填，仅 ADMIN） */
    FinPaymentRequest schedule(String id, String planDate);

    /** 待付款标记/恢复（SCHEDULED ↔ WAIT_FUNDS，仅 ADMIN） */
    FinPaymentRequest setWaitFunds(String id, boolean wait);

    /**
     * 执行付款（仅 ADMIN，状态 SCHEDULED）：超审批金额 L1 422 → SCAR 抵扣校验 →
     * FIFO 核销（PAID_AMOUNT 条件增量）→ 余额条件扣减 → 付款凭证 → 申请累计执行。
     * body = { reqId, payMethod, bankAccountId, payDate, applyAmount, deductIds[] }
     */
    Map<String, Object> execute(Map<String, Object> payload);

    /** 申请分页（供应商/状态/单号关键字/日期区间） */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String status,
                                   String keyword, String dateFrom, String dateTo);

    /** 申请详情（含发票、审批、付款与核销） */
    Map<String, Object> detail(String id);

    /** 可选发票：该供应商 POSTED 且未清 >0 的发票（含已付/未清） */
    List<Map<String, Object>> invoiceCandidates(String supplierId);

    /** 可抵扣扣款单：该供应商状态 TO_DEDUCT 的 SCAR 扣款单（执行付款勾选用） */
    List<Map<String, Object>> deductCandidates(String supplierId);

    /** 核销记录分页（KIND=PAYMENT/SETTLE） */
    Page<Map<String, Object>> writeoffPage(long current, long size, String kind,
                                           String supplierId, String invoiceNo);
}

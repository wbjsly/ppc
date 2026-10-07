package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinMatchResult;

import java.util.Map;

/**
 * 三方匹配（2.7.2，spec three-way-match）。
 * 发票登记 → PO-收货单-发票逐行比对（TOLERANCE_DEFAULT）→
 * 容差内自动冲回暂估 + 生成正式应付凭证；超容差冻结生成异常对账单，
 * 采购员确认后过账价差；跨期（PERIOD_CROSS）确认后仍须 ADMIN 手工过账。
 */
public interface ThreeWayMatchService {

    /** 发票登记（头 + 行）：同供应商发票号重复 422；缺 PO 关联仅登记不入匹配 */
    FinApInvoice createInvoice(Map<String, Object> payload);

    /** 发票分页（状态 / 供应商 / 发票号 / 日期区间） */
    Page<Map<String, Object>> invoicePage(long current, long size, String keyword, String status,
                                          String supplierId, String dateFrom, String dateTo);

    /** 发票详情（行 + 关联匹配单） */
    Map<String, Object> invoiceDetail(String id);

    /**
     * 执行匹配（PM/ADMIN）：逐行差异率 vs TOLERANCE_DEFAULT。
     * MATCHED → 自动冲回暂估 + 正式应付凭证 + 发票 POSTED（单事务）；
     * EXCEPTION → 暂估不冲、无凭证，落异常对账单 + 发票 EXCEPTION。
     * 未关联 PO 或该 PO 无过账收货 → 422。
     */
    Map<String, Object> match(String invoiceId);

    /** 采购员确认价差（PM/ADMIN）：必填意见；非跨期确认即过账，跨期留 CONFIRMED 待手工过账 */
    Map<String, Object> confirm(String matchId, String opinion);

    /** 跨期手工过账（仅 ADMIN，design D8）：生成当期价差调整凭证（PADJ），摘要注明跨期原因 */
    Map<String, Object> manualPost(String matchId, String note);

    /** 匹配台账分页（状态 / 供应商 / 发票号 / PO） */
    Page<Map<String, Object>> matchPage(long current, long size, String status, String supplierId,
                                        String invoiceNo, String poNo);

    /** 匹配单详情（含逐行差异明细） */
    Map<String, Object> matchDetail(String matchId);

    /** 合并迁移财务确认后重跑（BR-4.1-29）：对该供应商未过账且已关联 PO 的发票重跑匹配 */
    int rerunBySupplier(String supplierId);
}

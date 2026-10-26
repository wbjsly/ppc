package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.ExportDeal;
import com.erp.entity.fin.ExportRefundApply;

import java.util.Map;

/**
 * 出口退（免）税服务（M9-5-3，spec export-tax-refund，design D4/D5）。
 * 一票一档登记（FR-7-1）→ 单证齐全检查（FR-7-2 / C-4.14-13）→ 退税额计算（FR-7-3 / BR-41）
 * → 退税申报单状态机（FR-7-4 / BR-44 免抵退勾稽）→ 到账核销（FR-7-5 / BR-45）+ 分级预警。
 */
public interface ExportTaxRefundService {

    /** 一票一档登记（FOB 折算 BR-41 + 退税率 + 报关/发票不一致 L4 预警） */
    Map<String, Object> registerDeal(Map<String, Object> payload);

    /**
     * 单证齐全检查（FR-7-2）：返回三单状态 + 是否阻断 + 预警（临期/超期）。
     * 缺证阻断 422（L2：须税务主管豁免审批后 submitDealExempt 放行）。
     */
    Map<String, Object> checkDocuments(String dealId);

    /** 单证收齐登记（docDecl/docInvoice/docReceipt 置 1） */
    ExportDeal collectDocuments(String dealId, Map<String, Object> payload);

    /** 单证豁免登记（L2 审批通过后调用，留豁免原因痕） */
    ExportDeal approveExemption(String dealId, String reason);

    /** 创建退税申报单（聚合多票；RETURNED 挂起时暂停新批次；免抵退五值勾稽 BR-44） */
    Map<String, Object> createApply(Map<String, Object> payload);

    /** 提交 L2 审批（税务主管单节点） */
    Map<String, Object> submitApply(String applyId);

    /** 申报状态登记（SUBMITTED/ACCEPTED/AUDITING/APPROVED+核准额/RETURNED+补正清单/REJECTED） */
    Map<String, Object> registerStatus(String applyId, Map<String, Object> payload);

    /** 退税款到账（BR-45：一致核销+收款凭证；差异挂应收出口退税核查） */
    Map<String, Object> receive(String applyId, Map<String, Object> payload);

    /** 分级预警（截止前 EXPORT_REFUND_WARN_DAYS/2 天两级 + 收汇临期 30 天） */
    Map<String, Object> warnings();

    /** 一票一档分页 */
    Page<ExportDeal> dealPage(long current, long size, String keyword, String refundApplyNo);

    /** 退税申报单分页 */
    Page<ExportRefundApply> applyPage(long current, long size, String status);

    /** 申报单详情（含审批轨迹） */
    Map<String, Object> applyDetail(String applyId);
}

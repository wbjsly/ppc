package com.erp.service.qms;

import com.erp.entity.qms.CustomerAgreement;

import java.util.List;
import java.util.Map;

/**
 * 客户质量协议（change rework-inspection-tasks，spec customer-quality-agreement）。
 * 协议维护（区间重叠 422/停用不改历史/禁硬删）+ 投诉登记留痕 + OQC 建批命中加严。
 */
public interface CustomerAgreementService {

    List<CustomerAgreement> list(String customerId, String status);

    Map<String, Object> detail(String id);

    /** 新建/更新（DRAFT 可改；编码创建后不可改；ACTIVE 生效区间重叠 422） */
    CustomerAgreement save(CustomerAgreement agreement);

    /** 审批生效：DRAFT → ACTIVE */
    CustomerAgreement activate(String id);

    /** 停用：仅阻止新批命中（历史批快照不变），禁物理删除 */
    CustomerAgreement stop(String id);

    /** 投诉登记：COMPLAINT_STREAK +1，留痕（原因必填） */
    CustomerAgreement logComplaint(String id, String reason);

    /** 计数清零，留痕 */
    CustomerAgreement clearStreak(String id, String reason);

    /**
     * OQC 建批命中（spec 命中加严与投诉升级全检）：
     * 客户空 → 空 Map（不误命中）；命中 ACTIVE 协议 → {agreement, tightCtq, sampleOverride, fullInspect}
     * COMPLAINT_STREAK≥2 → fullInspect=true（100% 全检）。
     */
    Map<String, Object> hitFor(String customerId, String itemCode, String categoryCode);
}

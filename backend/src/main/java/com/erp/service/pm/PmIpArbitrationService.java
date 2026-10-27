package com.erp.service.pm;

import com.erp.entity.pm.PmAchievement;

import java.util.Map;

/**
 * IP 权属冲突仲裁（add-ip-management，FR-4.8-6-4 + C-4.8-04）。
 * 双入口：确认异议自动带出（CONFIRM_OBJECTION）+ 法务独立申请（MANUAL）；同一成果一个在途（409）。
 * 状态机：CLAIMS_SUBMITTED→LEGAL_REVIEWING→OPINION_ISSUED→PARTY_CONFIRMED→ADJUDICATED / ESCALATED_EXTERNAL。
 */
public interface PmIpArbitrationService {

    /** 确认异议自动带出仲裁单（PmAchievementService.object 调用） */
    Map<String, Object> createFromObjection(PmAchievement achievement);

    /** 法务独立申请（兜底入口） */
    Map<String, Object> createManual(Map<String, Object> payload);

    /** 提交权属主张（双方各自 record，永久留痕） */
    Map<String, Object> addClaim(String arbId, Map<String, Object> payload);

    /** 法务出具仲裁意见（→ OPINION_ISSUED） */
    Map<String, Object> issueOpinion(String arbId, Map<String, Object> payload);

    /** 双方确认意见（研发负责人代公司确认）；拒绝 → ESCALATED_EXTERNAL */
    Map<String, Object> confirmOpinion(String arbId, Map<String, Object> payload);

    /** 裁决：回写成果权属覆盖原判定 + 成果回待确认 + 仲裁单置 ADJUDICATED */
    Map<String, Object> adjudicate(String arbId, Map<String, Object> payload);

    /** 仲裁单列表（含主张明细） */
    Map<String, Object> list(Map<String, Object> query);
}

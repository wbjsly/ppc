package com.erp.service.mrp;

import com.erp.entity.mrp.TrialSummary;

import java.util.List;
import java.util.Map;

/**
 * 试产总结单与跨部门评审（change add-trial-production-management，spec trial-execution
 * 「试产总结与跨部门评审」FR-4.5-8-6）。
 * 三指标（良率/工时达成率及分项）服务端计算忽略客户端值；TrialReview 单 SEQ 三并行节点
 * （PROCESS_MGR/QUALITY_MGR/PLAN_MGR，design D10），全过 APPROVED、任一驳回 REJECTED。
 * APPROVED 是阶段闸口（DVT/PVT 前置）与转量产解锁依据。
 */
public interface TrialSummaryService {

    /** BIZ_TYPE（approval-workflow 底座；bizId = 总结单号 TS-） */
    String BIZ_TRIAL_REVIEW = "TrialReview";

    /**
     * 建档/刷新（1:1 upsert by MO_ID）：服务端重算良率与工时达成率
     * （请求体携带的指标字段一律忽略——spec「禁手填」）；仅 DRAFT/REJECTED 可改。
     */
    TrialSummary save(TrialSummary head);

    /** 单条（总结单号定位；缺省按 MO_ID 取） */
    TrialSummary get(String summaryNo);

    /** 按工单取（无则 null） */
    TrialSummary byMo(String moId);

    /**
     * 提交评审（DRAFT/REJECTED → PENDING）：PVT 阶段前置良率闸口
     * （YIELD_PCT < TRIAL_PVT_YIELD_MIN → 422「良率未达标」，design D10）。
     */
    TrialSummary submit(String summaryNo);

    /** 列表（阶段/状态过滤） */
    List<TrialSummary> list(String trialStage, String status);

    /**
     * 审批回调落库（ApprovalCallback 调用；不鉴权）：
     * 通过 → APPROVED 留痕；驳回 → 回 REJECTED 记意见（可改后重提）。
     */
    Map<String, Object> onApproval(String summaryNo, boolean approved, String opinion, String operator);
}

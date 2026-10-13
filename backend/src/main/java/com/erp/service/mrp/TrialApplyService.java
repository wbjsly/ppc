package com.erp.service.mrp;

import com.erp.entity.mrp.TrialApply;

import java.util.List;
import java.util.Map;

/**
 * 试产申请单（change add-trial-production-management，spec trial-order
 * 「试产申请单建档与校验」FR-4.5-8-1 / 「1:1 转开」）。
 * 建档校验（阶段/数量/验证目标）→ TrialApply 单节点工艺主管审批 → APPROVED 后转开（见 TrialOrderService）。
 */
public interface TrialApplyService {

    /** 建档（阶段 ∈ EVT/DVT/PVT、数量 > 0、验证目标非空白——缺失 422「退回补充」）；TA- 编号 */
    TrialApply create(TrialApply head);

    /** 提交审批（DRAFT → PENDING，TrialApply 单节点 ROLE_PROCESS_MGR；重复提交 422） */
    TrialApply submit(String trialNo);

    /** 单条查询（TRIAL_NO 定位，不存在 422） */
    TrialApply get(String trialNo);

    /** 列表（阶段/状态/产品过滤；缺省全部） */
    List<TrialApply> list(String trialStage, String status, String productCode);

    /**
     * 审批回调落库（ApprovalCallback 调用；不鉴权——审批底座已鉴权）：
     * 通过 → APPROVED 留痕；驳回 → 回 DRAFT 记意见（≥2 字由审批底座保证）。
     */
    Map<String, Object> onApproval(String trialNo, boolean approved, String opinion, String operator);
}

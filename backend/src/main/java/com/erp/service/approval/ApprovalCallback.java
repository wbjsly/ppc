package com.erp.service.approval;

import com.erp.entity.system.ApprovalInstance;

import java.util.Set;

/**
 * 审批业务回调（design D4）：业务方实现本接口并声明自己关心的 BIZ_TYPE。
 * 审批通过/驳回时在**同一事务**内回调，回调异常 → 审批与业务一并回滚（spec approval-workflow）。
 */
public interface ApprovalCallback {

    /** 本回调关心的业务类型（如 Concession、StandardPublish） */
    Set<String> supportedBizTypes();

    /** 通过：驱动业务状态迁移（如让步接收置 APPROVED、GR 行置 CONCESSION） */
    default void onApproved(ApprovalInstance instance) {
    }

    /** 驳回：业务回可修改态 */
    default void onRejected(ApprovalInstance instance) {
    }
}

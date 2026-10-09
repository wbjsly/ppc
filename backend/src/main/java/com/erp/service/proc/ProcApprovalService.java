package com.erp.service.proc;

import java.util.List;
import java.util.Map;

/**
 * 请购审批能力契约（2.1.4，FR-4.2-1-2 分级路由 + BR-4.2-10 超时升级）。
 * 提交聚合可提交态：CONFIRMED / PENDING_APPROVAL / PENDING_MODIFY；判级金额 Σ(qty×estUnitPrice)。
 */
public interface ProcApprovalService {

    /** 提交审批：按限额生成节点任务序列 → APPROVING；重提批次 +1（历史保留） */
    Map<String, Object> submit(String prId);

    /** 节点通过：末节点 → APPROVED（事件），否则激活下一节点；返回该 PR 审批日志 */
    java.util.List<Map<String, Object>> pass(String taskId);

    /** 节点驳回（原因≥2字）：任务 REJECTED、余下 SUPERSEDED、头 → PENDING_MODIFY（事件）；返回日志 */
    java.util.List<Map<String, Object>> reject(String taskId, String reason);

    /** 待办列表（先 sweep）：含判级金额/路由链/超时升级标记/同物料历史预估价辅助 */
    List<Map<String, Object>> todo();

    /** 按 PR 的审批日志（批次/节点/动作/原因/人/时间，倒序） */
    List<Map<String, Object>> logs(String prId);
}

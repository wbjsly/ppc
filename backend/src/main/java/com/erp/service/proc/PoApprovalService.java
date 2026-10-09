package com.erp.service.proc;

import com.erp.entity.proc.PurchaseOrder;

import java.util.List;
import java.util.Map;

/**
 * PO 独立审批能力契约（change add-framework-agreement-order，design D1：B-b 不改造 ProcApprovalService）。
 * 三档判级 FR-4.2-3-2：常规→采购经理；超预算→采购经理→采购总监；特殊→采购总监→分管副总裁；
 * 价控命中（BR-4.2-15/17）强制链尾追加采购总监。审批动作：批准/驳回（原因≥2字）/条件批准。
 */
public interface PoApprovalService {

    /**
     * 生成审批节点序列并激活首节点（DRAFT 已在调用方转 APPROVING 后进入）。
     * 判级顺序：特殊 > 超预算 > 常规；价控 ESCALATE 追加采购总监（已在链中则仅标记）。
     */
    List<Map<String, Object>> createTasks(PurchaseOrder po);

    /** 当前节点通过：末节点 → PO APPROVED（已下达）；否则激活下一节点。返回该 PO 审批日志 */
    List<Map<String, Object>> pass(String taskId);

    /** 当前节点条件批准：同通过，动作记 CONDITIONAL 并保存附加条件 */
    List<Map<String, Object>> passConditional(String taskId, String conditionText);

    /** 驳回（原因≥2字）：当前节点 REJECTED、余节点 SUPERSEDED、PO 回 DRAFT（可改重提批次+1） */
    List<Map<String, Object>> reject(String taskId, String reason);

    /** 待办：全部 ACTIVE 节点任务 + PO 摘要 + 路由链 + 判级档位 + 价控/比价信息（含 escalate 标记） */
    List<Map<String, Object>> todo();

    /** 按 PO 的审批日志（批次/节点/动作/原因/人/时间，倒序） */
    List<Map<String, Object>> logs(String poId);
}

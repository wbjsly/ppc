package com.erp.service.approval;

import com.erp.entity.system.ApprovalInstance;

import java.util.List;
import java.util.Map;

/**
 * 通用审批底座（change add-quality-collaboration design D4，spec approval-workflow）。
 * 三形态：串行链（每 SEQ 单节点）/ 并行双签（同 SEQ 两节点全过）/ 会签（同 SEQ 多节点全过）。
 * 偏差 D6：与既有 PR/PO/招标审批双轨并存，旧审批不迁移。
 */
public interface ApprovalEngine {

    /**
     * 提交审批：实例 + 节点同事务写入；同一业务已存在 PENDING 实例 → 422。
     *
     * @param bizType    业务类型（Concession / StandardPublish / Exempt / NcrDisposition / Capa / Scar / CopqFinance / Return / ExemptFire...）
     * @param bizId      业务单据 ID
     * @param title      审批标题
     * @param escalateTo 超时升级角色（空则不升级）
     * @param seqGroups  外层 = SEQ 序（1 起），内层 = 该 SEQ 并行节点
     */
    ApprovalInstance submit(String bizType, String bizId, String title, String escalateTo,
                            List<List<ApprovalNodeSpec>> seqGroups);

    /** 节点通过：角色不符 403；节点/实例非可签态 422；并发签署靠乐观锁 422 */
    ApprovalInstance pass(String taskId, String opinion);

    /** 节点驳回（意见 ≥2 字）：整单 REJECTED，未激活节点 SKIPPED */
    ApprovalInstance reject(String taskId, String opinion);

    ApprovalInstance getInstance(String id);

    ApprovalInstance findByBiz(String bizType, String bizId);

    /** 当前用户待办：可签节点 + 已升级通知 */
    List<Map<String, Object>> todo();

    /** 按业务单据的审批日志（实例 + 节点按 SEQ 排序） */
    Map<String, Object> logs(String bizType, String bizId);

    /** 超时扫描：72h 提醒、7 天升级（幂等），返回处理条数 */
    int sweepTimeouts();
}

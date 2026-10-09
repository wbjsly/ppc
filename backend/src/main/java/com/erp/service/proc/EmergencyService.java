package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 紧急采购能力契约（2.1.3，S-4.2-04 + BR-4.2-11 + L1321）。
 * 全部状态迁移经 EmergencySupport.transition（状态机单点）；查询前执行懒 sweep。
 */
public interface EmergencyService {

    /** 分页（先 sweep：特批超时打标 + 逾期例外联动阻断通道） */
    Page<Map<String, Object>> page(long current, long size, String status, String keyword);

    /** 详情 */
    Map<String, Object> detail(String id);

    /** PR 候选（APPROVED/PENDING_RFQ，带判级金额与行摘要） */
    Map<String, Object> prCandidates(String keyword);

    /** 发起：通道 BLOCKED L1 → PR 状态 → 同 PR 防重 → 单号 + CREATED 事件 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 特批通过（原因≥2字 → APPROVED_EMERGENCY + 放行截止日 + SPECIAL_APPROVED 事件） */
    Map<String, Object> approve(String id, String reason);

    /** 特批驳回（原因≥2字 → REJECTED + 事件） */
    Map<String, Object> reject(String id, String reason);

    /** 比价补齐登记（APPROVED_EMERGENCY|FILLING 受理：先迁 FILLING 再 COMPLETED，FILLED 事件一次） */
    Map<String, Object> fill(String id, Map<String, Object> payload);

    /** 人工关闭（任意非终态，原因必填） */
    void close(String id, String reason);

    /** 通道台账列表（含无行账号「已开通（默认）」与关联逾期数，筛选分页） */
    Map<String, Object> channels(long current, long size, String status, String keyword);

    /** 总监复核恢复：BLOCKED→OPEN + 复核留痕 + 关闭该账号全部逾期例外（CLOSED 事件） */
    Map<String, Object> restoreChannel(String accountId, String note);

    /** RFQ/PO 放行桩：按 PR 返回放行结构（无 → emergency:false 明示） */
    Map<String, Object> clearance(String prNo);
}

package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.proc.PurchaseOrder;

import java.util.List;
import java.util.Map;

/**
 * PO 交期协同（spec po-collaboration）：推送 / 48h 确认 / 72h 升级 /
 * 连续 3 张锁定（design D4 惰性判定 + 幂等动作行，design D5 锁确认入口不锁推送）。
 */
public interface PoCoopService {

    /** 审批通过/新版本下达时调用：写 PUSHED 流水 + 发布 PROC.PO_PUSHED（同版本幂等跳过） */
    void push(PurchaseOrder po);

    /** 交期确认（source=PORTAL 门户 / OFFLINE 代录）：写 CONFIRM 行 + 回写 promiseDate + 事件 */
    Map<String, Object> confirm(String poId, Map<String, Object> payload, String source);

    /** 供应商改期/改量申请 → CHANGE_REQUEST 行 + PO 置 CHANGE_PENDING（生成交期变更通知） */
    Map<String, Object> changeRequest(String poId, Map<String, Object> payload);

    /** 采购员接受改期 → CHANGE_ACCEPTED 行 + promiseDate 写回 + CONFIRMED + 事件 */
    Map<String, Object> acceptChange(String poId, Map<String, Object> payload);

    /** 催办补发（ADMIN/PM 手工；惰性自动催办走 ensureTimeout） */
    Map<String, Object> remind(String poId);

    /** 采购经理/ADMIN 解锁（写 UNLOCK 行 + 清 PO 锁定标记） */
    Map<String, Object> unlock(String supplierId, String remark);

    /** 内部分页（含惰性超时补写与锁定判定） */
    Page<Map<String, Object>> page(long current, long size, String status,
                                   String supplierId, String keyword);

    /** 门户分页（绑定供应商，行级隔离 + 惰性超时补写） */
    Page<Map<String, Object>> portalPage(long current, long size, String keyword, String supplierId);

    /** 时间线（PUSHED/REMIND/ESCALATE/CONFIRM/CHANGE_REQUEST/CHANGE_ACCEPTED/LOCK/UNLOCK 升序） */
    List<Map<String, Object>> timeline(String poId);

    /** 该供应商是否处于锁定态（最新 LOCK 晚于最新 UNLOCK） */
    boolean supplierLocked(String supplierId);
}

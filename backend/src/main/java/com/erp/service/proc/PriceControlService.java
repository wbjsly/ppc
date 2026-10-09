package com.erp.service.proc;

import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;

import java.util.List;
import java.util.Map;

/**
 * 价控三重校验引擎（change add-framework-agreement-order，2.3.5，spec purchase-price-control）。
 * 固定优先级 合同价（=框架协议价，偏差 D1）> 历史价（近 3 次已下达均价，0 次 NO_HISTORY 放行，偏差 D2）
 * > 预算（科目本年累计 vs 预算 × 110%），BR-4.2-19 命中即停，全程落 erp_proc_po_price_control 日志。
 * 阻断（历史价偏离 > 0.5%）→ 提交 422 并提示"申请特批"；特批填原因后转升级审批链（FR-4.2-3-1 异常处理）。
 */
public interface PriceControlService {

    /**
     * 提交时执行价控（写日志 + 回填行 priceCtrlResult）。
     * @param specialReason 特批原因（非空时 BLOCK 降级为 ESCALATE 走升级链，须 ≥2 字）
     * @return 本次执行的日志摘要（按级别顺序）
     * @throws com.erp.common.ServiceException 422 存在 BLOCK 且未特批
     */
    List<Map<String, Object>> execute(PurchaseOrder po, List<PurchaseOrderLine> lines,
                                      String specialReason);

    /** 按 PO 查询价控日志（2.3.5 页面 / 审批展示，倒序） */
    List<Map<String, Object>> logs(String poId);
}

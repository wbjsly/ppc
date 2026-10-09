package com.erp.service.inv;

import java.util.Map;

/**
 * 行级扫码确认（4.7.2，spec picking-review 行级扫码确认；档位 2）：
 * 码校验（仓位/物料/批次实时比对，不符 L1 阻断+失败记录）→ 录实拣数 → 确认本行。
 */
public interface PickScanService {

    /**
     * 行级三码校验（BR-4.4-25/26/27）：逐项与任务行比对（期望值为空的项跳过）。
     * 不符 → 422 + 扫码校验记录 FAIL；批次不符同时登记 BATCH 差异行（任务挂 DIFF_PENDING）。
     * 全匹配 → 行 SCAN_OK_FLAG=1（放行）。
     *
     * @param binCode/itemCode/batchNo 扫码输入值（可空=未扫该项）
     */
    Map<String, Object> verify(String taskId, Integer lineNo, String binCode,
                               String itemCode, String batchNo);

    /**
     * 确认本行：前置 = 任务 PICKING 且行已放行（SCAN_OK_FLAG=1）。
     * 实拣 = 应拣 → 行 PICKED + 扫码放行记录；全行 PICKED → 任务 PICKED。
     * 实拣 < 应拣 → 422 提示走差异登记（registerShort）；实拣 > 应拣 → 422。
     * 序列物料 MUST 录入序列号且在库可用（否则 422，C-4.4-02）。
     */
    Map<String, Object> confirmLine(String taskId, Integer lineNo, java.math.BigDecimal pickedQty,
                                    String serials);

    /**
     * 短少登记（BR-4.4-28）：行置 SHORT + 登记 QTY 差异（registerDiff：任务 DIFF_PENDING +
     * 销售单差额释放预留）。
     */
    Map<String, Object> registerShort(String taskId, Integer lineNo,
                                      java.math.BigDecimal actualQty, String reason);
}

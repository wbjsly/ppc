package com.erp.service.inv;

import java.util.Map;

/**
 * 出库复核（4.7.3，spec picking-review 出库复核三分支）：
 * 通过 / 数量品种差异（DIFF_PENDING + 锁过账）/ 外观异常（自动发起质量冻结 + QUALITY_PENDING）。
 */
public interface PickReviewService {

    /**
     * 行级复核结论（任务须 REVIEWING，首次复核自动 PICKED → REVIEWING）。
     *
     * @param result PASS / DIFF / QUALITY
     * @param kind   DIFF 时必填：QTY / BATCH（QUALITY 分支内部固定 QUALITY）
     * @param reason QUALITY 必填；DIFF 建议填（差异说明经 registerDiff 落 DIFF_NOTE）
     * @param expectQty/actualQty DIFF 数量类差异的应拣/复核实数（BATCH 差异可空）
     */
    Map<String, Object> review(String taskId, Integer lineNo, String result, String kind,
                               String reason, java.math.BigDecimal expectQty,
                               java.math.BigDecimal actualQty);

    /**
     * 退回补拣（spec 场景）：DIFF_PENDING → PICKING，该行回待拣（清实拣/放行标记）；
     * 差异行保持 PENDING 至闭环。
     */
    Map<String, Object> returnToPick(String taskId, Integer lineNo);
}

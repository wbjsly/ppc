package com.erp.service.inv;

import com.erp.entity.inv.InvCheckDiff;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 拣货差异（4.7.4，spec picking-review 实拣差异生成与预留释放 / 差异单台账与闭环）：
 * registerDiff 为 4.7.2 短少与批次不符、4.7.3 复核差异的统一入口（design D5 三触发点收敛）。
 */
public interface PickDiffService {

    /**
     * 登记差异行（幂等：同任务+行+类型已有 PENDING 则返回已有）：
     * ① 落差异行（DIFF_TYPE=PICK）；② 任务置 DIFF_PENDING（QUALITY_PENDING 不降级，
     *    已在分支态幂等跳过，CREATED/终态 422）；③ SALES_OUT 按差额释放该行 ATP 预留
     *    （无预留/非销售单空操作不报错，偏差 D4）。
     *
     * @param reason 差异说明（登记必填）
     */
    InvCheckDiff registerDiff(String taskId, Integer lineNo, String kind,
                              BigDecimal expectQty, BigDecimal actualQty, String reason);

    /** 差异台账分页（DIFF_TYPE=PICK；状态/类型/单据/物料筛选） */
    Map<String, Object> page(String status, String kind, String docNo, String itemCode,
                             long current, long size);

    /**
     * 闭环（主管确认，处理说明必填 422）：PENDING→RESOLVED；
     * 该任务全部差异 RESOLVED 后门闩解除并回迁——存在未复核行 → PICKING，否则 DONE。
     */
    Map<String, Object> close(String diffId, String note);
}

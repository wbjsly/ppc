package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 拣货任务行（spec picking-review 行级扫码确认）：
 * 批次/仓位来自 4.6.3 回写单据行，MUST NOT 重新计算推荐；行状态 PENDING→PICKED（SHORT=短少转差异）。
 */
@Getter
@Setter
@TableName("erp_pick_task_line")
public class PickTaskLine extends BaseEntity {

    public static final String LS_PENDING = "PENDING";
    public static final String LS_PICKED = "PICKED";
    public static final String LS_SHORT = "SHORT";

    /** 复核结论 */
    public static final String RV_PASS = "PASS";
    public static final String RV_DIFF = "DIFF";
    public static final String RV_QUALITY = "QUALITY";

    private String taskId;

    private Integer lineNo;

    /** 来源单据行 ID */
    private String srcLineId;

    private String itemCode;

    private String itemName;

    private String warehouseCode;

    /** 4.6.3 回写批次 */
    private String batchNo;

    /** 4.6.3 回写仓位 */
    private String binCode;

    /** 应拣数量 */
    private BigDecimal qty;

    /** 实拣数量 */
    private BigDecimal pickedQty;

    private String lineStatus;

    /** 码校验放行标记 1/0 */
    private String scanOkFlag;

    /** PASS / DIFF / QUALITY */
    private String reviewResult;

    /** 录入序列号（逗号分隔） */
    private String serials;

    /** WAVE 任务行归属明细 JSON：[{shipId, shipNo, qty}]（分播按订单拆回，spec wave-management） */
    private String srcAlloc;
}

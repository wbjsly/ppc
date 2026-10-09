package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售退货判定记录（tasks 12.1/12.3，spec sales-return）：
 * 判定（责任方/核定数量/超期/依据）、判定驳回、提交审批、实物入库、退款、换货全链留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_return_judge_log")
public class SdReturnJudgeLog extends BaseEntity {

    public static final String OP_JUDGE = "JUDGE";
    public static final String OP_JUDGE_REJECT = "JUDGE_REJECT";
    public static final String OP_SUBMIT = "SUBMIT";
    public static final String OP_STOCK_IN = "STOCK_IN";
    public static final String OP_REFUND = "REFUND";
    public static final String OP_EXCHANGE = "EXCHANGE";

    private String returnId;
    private String returnNo;
    private String opType;
    private String liability;
    private String judgeHandle;
    private String overdue;
    private BigDecimal judgeQty;
    /** 判定依据 / 操作说明 */
    private String basis;
    /** 行级明细 JSON */
    private String detail;
    private String opBy;
    private LocalDateTime opAt;
}

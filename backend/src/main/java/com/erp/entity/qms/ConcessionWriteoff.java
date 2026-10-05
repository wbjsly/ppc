package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 让步接收核销记录（tasks 7.4，spec concession-acceptance）：
 * 逐次校验有效期/累计量/使用范围，越界 422 且落 REJECTED 记录（推送质量经理）；
 * 核销留痕（操作人/时间/剩余额度快照）。
 */
@Getter
@Setter
@TableName("erp_qms_concession_writeoff")
public class ConcessionWriteoff extends BaseEntity {

    @TableField("CONCESSION_ID")
    private String concessionId;

    @TableField("CONCESSION_NO")
    private String concessionNo;

    @TableField("NCR_ID")
    private String ncrId;

    @TableField("ITEM_CODE")
    private String itemCode;

    @TableField("BATCH_NO")
    private String batchNo;

    /** 本次核销数量 */
    @TableField("QTY")
    private BigDecimal qty;

    /** 本次使用范围（出库用途） */
    @TableField("SCOPE")
    private String scope;

    /** OK / REJECTED */
    @TableField("STATUS")
    private String status;

    /** 越界原因（REJECTED 时写入） */
    @TableField("REJECT_REASON")
    private String rejectReason;

    /** 核销后剩余额度快照 */
    @TableField("REMAIN_QTY")
    private BigDecimal remainQty;

    /** 关联出库/领用单号（4.5 出库接入桩 D8） */
    @TableField("REF_DOC_NO")
    private String refDocNo;

    @TableField("OPERATOR")
    private String operator;

    @TableField("OPERATOR_NAME")
    private String operatorName;

    @TableField("WRITE_DATE")
    private LocalDateTime writeDate;

    @TableField("REMARK")
    private String remark;
}

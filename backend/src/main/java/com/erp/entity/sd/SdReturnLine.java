package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 销售退货申请行（tasks 12.1 提前建；SHIP_LINE_ID 支持拒收自动带入）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_return_line")
public class SdReturnLine extends BaseEntity {

    public static final String LS_PENDING = "PENDING";

    private String returnId;
    private Integer lineNo;
    private String soLineId;
    private Integer soLineNo;
    /** 来源发货行（拒收带入） */
    private String shipLineId;
    private String itemCode;
    private String itemName;
    /** 退货数量（≤ 已发可退余量） */
    private BigDecimal qty;
    /** 核定可退数量（NULL = 未判定，12.3） */
    private BigDecimal judgeQty;
    /** 已实物入库数量（12.7） */
    private BigDecimal inQty;
    /** 回补批次号 */
    private String stockBatchNo;
    /** 1 = 入待检 QC_QTY（不计 ATP，12.7） */
    private String qcFlag;
    private String baseUnit;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouseCode;
    private String lineStatus;
    private String remark;
}

package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 退货单行：未过账质检退货按 PO 单价计价，已入库按原入库单价（BR-4.2-32）。
 */
@Getter
@Setter
@TableName("erp_proc_return_line")
public class ReturnOrderLine extends BaseEntity {
    /** 退货单 ID */
    @TableField("RETURN_ID")
    private String returnId;

    /** 行号 */
    @TableField("LINE_NO")
    private Integer lineNo;

    /** PO 行 */
    @TableField("PO_LINE_ID")
    private String poLineId;

    /** 收货行 */
    @TableField("GR_LINE_ID")
    private String grLineId;

    /** 物料编码 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 物料名称 */
    @TableField("ITEM_NAME")
    private String itemName;

    /** 单位 */
    @TableField("UNIT")
    private String unit;

    /** 批次 */
    @TableField("BATCH_NO")
    private String batchNo;

    /** 退货数量 */
    @TableField("QTY")
    private java.math.BigDecimal qty;

    /** 计价单价 */
    @TableField("UNIT_PRICE")
    private java.math.BigDecimal unitPrice;

    /** 金额 */
    @TableField("AMOUNT")
    private java.math.BigDecimal amount;

    /** 行备注 */
    @TableField("LINE_REMARK")
    private String lineRemark;

}

package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 调拨单行（spec transfer-order：行级内部转移价必填 >0，取价方案 a）。
 * BATCH_NO 空 = 出库过账时引擎 FIFO 分配（结果写回头部 ALLOC_JSON）。
 */
@Getter
@Setter
@TableName("erp_inv_transfer_order_line")
public class InvTransferOrderLine extends BaseEntity {

    private String orderId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private String batchNo;

    /** 拣货推荐回写仓位（4.6.3；空=过账时引擎位级 FIFO 分配） */
    private String binCode;
    private BigDecimal qty;
    /** 内部转移价（必填 >0，跨法人凭证/发票金额依据） */
    private BigDecimal internalPrice;
    private BigDecimal lineAmount;
}

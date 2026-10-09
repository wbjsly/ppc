package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 报废单行（spec scrap-order：库龄快照创建时固化，单位成本为两步凭证金额依据）。
 */
@Getter
@Setter
@TableName("erp_inv_scrap_order_line")
public class InvScrapOrderLine extends BaseEntity {

    private String orderId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private String batchNo;

    /** 拣货推荐回写仓位（4.6.3；空=过账时引擎位级 FIFO 分配） */
    private String binCode;
    private BigDecimal qty;
    /** 库龄快照（创建时按该仓库该批次位行 MIN(INBOUND_DATE) 距今天数固化） */
    private Integer stockAgeDays;
    /** 单位成本（凭证金额依据；过账前须 >0） */
    private BigDecimal unitCost;
    /** 序列号清单（逗号分隔，serialFlag=1 物料必填——C-4.4-02 引擎判重入参） */
    private String serials;
    private BigDecimal lineAmount;
}

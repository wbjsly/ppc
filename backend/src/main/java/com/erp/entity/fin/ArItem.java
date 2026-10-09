package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 应收明细行（task 10.1，spec 月度对账未核销逐笔明细的物料级支撑）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_item")
public class ArItem extends BaseEntity {

    private String arId;
    private String arNo;
    private Integer lineNo;
    private String soLineId;
    private String shipLineId;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private String baseUnit;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private String remark;
}

package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 供应商发票行（2.7.2）：关联 PO 行，携带数量/单价/金额供三单比对。 */
@Getter
@Setter
@TableName("erp_fin_ap_invoice_line")
public class FinApInvoiceLine extends BaseEntity {

    private String invoiceId;
    private Integer lineNo;
    private String poId;
    private String poNo;
    private String poLineId;
    private String itemCode;
    private String itemName;
    private String unit;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
}

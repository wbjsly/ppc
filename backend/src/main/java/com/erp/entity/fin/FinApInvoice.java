package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 供应商发票头（2.7.2，spec three-way-match）。
 * 发票号同供应商内唯一；缺 PO 关联仅登记不入自动匹配。
 */
@Getter
@Setter
@TableName("erp_fin_ap_invoice")
public class FinApInvoice extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_MATCHED = "MATCHED";
    public static final String ST_EXCEPTION = "EXCEPTION";
    public static final String ST_POSTED = "POSTED";

    private String invoiceNo;
    private String supplierId;
    private String supplierName;
    private LocalDate invoiceDate;
    private String currency;
    /** 含税总额（本期不拆进项税，design D9） */
    private BigDecimal totalAmount;
    /** 已核销金额（FIFO 核销，047 增列；未清 = totalAmount − paidAmount） */
    private BigDecimal paidAmount;
    private String poNo;
    /** 关联 VMI 结算单 ID（CONSIGN PO 发票必填，048 增列 / MODIFIED three-way-match） */
    private String settleId;
    /** DRAFT / MATCHED / EXCEPTION / POSTED */
    private String status;
    private String remark;
}

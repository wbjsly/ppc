package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商报价（FR-4.2-2-2，RFQ×SUPPLIER 唯一）。
 * 谈判双轨（BR-4.2-14）：原始单价不动，谈判后另列；异常偏离（BR-4.2-12）确认/剔除。
 */
@Data
@TableName("erp_proc_quote")
public class Quote implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String rfqId;

    private String supplierId;

    private BigDecimal unitPrice;

    private Integer leadTimeDays;

    private BigDecimal moq;

    /** NET30 / NET60 / NET90 / 款到发货 */
    private String paymentTerms;

    private LocalDate quoteValidDate;

    // ---- 谈判双轨 ----
    private BigDecimal negotiatedPrice;
    private String negotiateNote;

    // ---- 异常偏离（BR-4.2-12） ----
    private String anomalyFlag;
    private String anomalyConfirmed;
    private String anomalyConfirmBy;
    private LocalDateTime anomalyConfirmDate;

    // ---- 剔除 ----
    private String excluded;
    private String excludedReason;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}

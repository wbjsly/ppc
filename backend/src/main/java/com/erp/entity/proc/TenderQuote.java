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
 * 招标多轮报价（BR-4.2-06 只降不升；design D2 单一总截止 + ROUND_NO 轮次）。
 * TENDER_ID × SUPPLIER_ID × ROUND_NO 唯一；历史轮次只读。
 */
@Data
@TableName("erp_proc_tender_quote")
public class TenderQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private String supplierId;

    /** 轮次，自 1 起递增 */
    private Integer roundNo;

    private BigDecimal unitPrice;

    private Integer leadTimeDays;

    private BigDecimal moq;

    /** NET30 / NET60 / NET90 / 款到发货 */
    private String paymentTerms;

    private LocalDate quoteValidDate;

    private LocalDateTime quoteTime;

    /** 代录人（内部端由采购员代录，偏差 D2） */
    private String operator;

    private String remark;

    private String createBy;

    private LocalDateTime createDate;
}

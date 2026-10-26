package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检验合格证（change rework-inspection-tasks，spec inspection-lot 合格证）。
 * OQC 放行同事务生成；D-3 偏差：无防伪编号体系，CERT_NO 仅流水单号。
 */
@Getter
@Setter
@TableName("erp_qms_certificate")
public class Certificate extends BaseEntity {

    /** 合格证号（C + yyyyMMdd + - + 6 位流水） */
    @TableField("CERT_NO")
    private String certNo;

    @TableField("LOT_NO")
    private String lotNo;

    @TableField("LOT_ID")
    private String lotId;

    @TableField("ITEM_CODE")
    private String itemCode;

    @TableField("ITEM_NAME")
    private String itemName;

    @TableField("QTY")
    private BigDecimal qty;

    /** 客户（发货关联，无则空） */
    @TableField("CUSTOMER_NAME")
    private String customerName;

    @TableField("RESULT")
    private String result;

    @TableField("ISSUED_BY")
    private String issuedBy;

    @TableField("ISSUED_DATE")
    private LocalDateTime issuedDate;
}

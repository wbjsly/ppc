package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 采购框架协议明细（中标单价 + 份额分配，FR-4.2-10-3）。
 * 表含 VER_NO 但无 DEL_FLAG，故不继承 BaseEntity；价格与份额锁定，变更须走审批。
 */
@Data
@TableName("erp_proc_framework_agreement_line")
public class FrameworkAgreementLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String agreementId;

    private Integer lineNo;

    private String itemCode;

    private String itemName;

    private String awardSupplierId;

    private String awardSupplierName;

    /** 中标单价（锁定，直接改回 422） */
    private BigDecimal unitPrice;

    /** 份额分配（%） */
    private BigDecimal sharePct;

    private String createBy;

    private java.time.LocalDateTime createDate;

    private String updateBy;

    private java.time.LocalDateTime updateDate;

    private Integer verNo;
}

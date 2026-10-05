package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SCAR 扣款单（BR-4.12-47，偏差 D3）：待财务确认 → 已确认 → 待抵扣；
 * 货款实际抵扣记 TODO-NOTIFY 桩（2.7/8.3 未建）；争议可置 DISPUTED 暂挂。
 */
@Getter
@Setter
@TableName("erp_qms_scar_deduction")
public class ScarDeduction extends BaseEntity {
    /** SD + yyyyMMdd + - + 6 位流水 */
    @TableField("DEDUCT_NO")
    private String deductNo;

    /** 关联 SCAR */
    @TableField("SCAR_ID")
    private String scarId;

    /** SCAR 号 */
    @TableField("SCAR_NO")
    private String scarNo;

    /** 供应商 */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** 扣款金额 */
    @TableField("AMOUNT")
    private java.math.BigDecimal amount;

    /** PENDING_FINANCE / CONFIRMED / TO_DEDUCT / DISPUTED */
    @TableField("STATUS")
    private String status;

    /** 财务确认审批实例 */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 确认人 */
    @TableField("CONFIRM_BY")
    private String confirmBy;

    /** 确认时间 */
    @TableField("CONFIRM_DATE")
    private LocalDateTime confirmDate;

    /** 争议原因 */
    @TableField("DISPUTE_REASON")
    private String disputeReason;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}

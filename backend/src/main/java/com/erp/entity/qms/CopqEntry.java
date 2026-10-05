package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 质量成本归集明细（spec quality-cost，偏差 D2）：
 * 四类成本枚举直存（无会计科目映射），财务确认走审批底座 CopqFinance 节点；
 * 单笔 ≥10× 近 3 月月均 → ANOMALY_HOLD 阻断入报表（BR-4.12-39）。
 */
@Getter
@Setter
@TableName("erp_qms_copq_entry")
public class CopqEntry extends BaseEntity {
    /** CO + yyyyMMdd + - + 6 位流水 */
    @TableField("ENTRY_NO")
    private String entryNo;

    /** PREVENTION/APPRAISAL/INTERNAL_FAILURE/EXTERNAL_FAILURE */
    @TableField("CATEGORY")
    private String category;

    /** 金额 */
    @TableField("AMOUNT")
    private java.math.BigDecimal amount;

    /** NCR / RETURN / SCAR / CAPA / MANUAL */
    @TableField("SOURCE_TYPE")
    private String sourceType;

    /** 来源单据 ID */
    @TableField("SOURCE_ID")
    private String sourceId;

    /** 来源单据号 */
    @TableField("SOURCE_NO")
    private String sourceNo;

    /** 关联 NCR */
    @TableField("NCR_ID")
    private String ncrId;

    /** 关联 SCAR */
    @TableField("SCAR_ID")
    private String scarId;

    /** 物料 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 批次 */
    @TableField("BATCH_NO")
    private String batchNo;

    /** 供应商 */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** 1=无法归因（未归因类别） */
    @TableField("UNATTRIBUTED_FLAG")
    private String unattributedFlag;

    /** 业务发生日 */
    @TableField("OCCUR_DATE")
    private LocalDate occurDate;

    /** PENDING_FINANCE / CONFIRMED / ANOMALY_HOLD */
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

    /** 1=金额异常待核 */
    @TableField("ANOMALY_FLAG")
    private String anomalyFlag;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}

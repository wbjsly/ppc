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
 * 让步接收申请（spec concession-acceptance，C-4.12-01 双签）：
 * 原因/技术评估/风险评估/限制条件必填；仅限该批次；
 * 限制条件（有效期/上限/使用范围）过账时写入库存快照（D8 口径）。
 */
@Getter
@Setter
@TableName("erp_qms_concession")
public class Concession extends BaseEntity {
    /** CC + yyyyMMdd + - + 6 位流水 */
    @TableField("CONCESSION_NO")
    private String concessionNo;

    /** 关联 NCR */
    @TableField("NCR_ID")
    private String ncrId;

    /** NCR 号 */
    @TableField("NCR_NO")
    private String ncrNo;

    /** 关联检验批 */
    @TableField("LOT_ID")
    private String lotId;

    /** 关联收货单 */
    @TableField("GR_ID")
    private String grId;

    /** 关联收货行 */
    @TableField("GR_LINE_ID")
    private String grLineId;

    /** 物料编码 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 物料名称 */
    @TableField("ITEM_NAME")
    private String itemName;

    /** 批次 */
    @TableField("BATCH_NO")
    private String batchNo;

    /** 让步数量 */
    @TableField("QTY")
    private java.math.BigDecimal qty;

    /** 让步原因（必填） */
    @TableField("REASON")
    private String reason;

    /** 技术评估结论 */
    @TableField("TECH_ASSESS")
    private String techAssess;

    /** 风险评估 */
    @TableField("RISK_ASSESS")
    private String riskAssess;

    /** 使用有效期至 */
    @TableField("LIMIT_UNTIL")
    private LocalDate limitUntil;

    /** 批准数量上限 */
    @TableField("LIMIT_QTY")
    private java.math.BigDecimal limitQty;

    /** 使用范围（客户/产品/场地） */
    @TableField("LIMIT_SCOPE")
    private String limitScope;

    /** DRAFT / PENDING_APPROVE / APPROVED / REJECTED */
    @TableField("STATUS")
    private String status;

    /** 双签审批实例 ID */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 提交人 */
    @TableField("SUBMITTED_BY")
    private String submittedBy;

    /** 提交时间 */
    @TableField("SUBMITTED_DATE")
    private LocalDateTime submittedDate;

    /** 驳回原因 */
    @TableField("REJECT_REASON")
    private String rejectReason;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}

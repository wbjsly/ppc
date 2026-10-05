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
 * 供应商质量索赔 SCAR（spec supplier-quality-claim）：
 * SENT 起该供应商来料强制加严（BR-4.12-44），连 3 批合格解除；
 * 超 scar-reply-days 未回复升级采购经理并扣分（BR-4.12-45）；
 * 供应商 8D 回复由 SQE 代录（偏差 D7）。
 */
@Getter
@Setter
@TableName("erp_qms_scar")
public class Scar extends BaseEntity {
    /** SC + yyyyMMdd + - + 6 位流水 */
    @TableField("SCAR_NO")
    private String scarNo;

    /** 来源 NCR */
    @TableField("NCR_ID")
    private String ncrId;

    /** NCR 号 */
    @TableField("NCR_NO")
    private String ncrNo;

    /** 供应商 */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** NCR_RETURN / REPEAT / STOPPAGE */
    @TableField("TRIGGER_TYPE")
    private String triggerType;

    /** 标题 */
    @TableField("TITLE")
    private String title;

    /** 不合格描述 */
    @TableField("DEFECT_DESC")
    private String defectDesc;

    /** 索赔明细（工时/返工/停线/检验费） */
    @TableField("CLAIM_ITEMS")
    private String claimItems;

    /** 索赔金额 */
    @TableField("CLAIM_AMOUNT")
    private java.math.BigDecimal claimAmount;

    /** 回复时限 */
    @TableField("REPLY_DUE_DATE")
    private LocalDate replyDueDate;

    /** DRAFT / SENT / REPLYING / VERIFYING / CLOSED */
    @TableField("STATUS")
    private String status;

    /** 重大 SCAR 审批实例 */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 发出时间 */
    @TableField("SENT_DATE")
    private LocalDateTime sentDate;

    /** 1=超期已升级 */
    @TableField("ESCALATED_FLAG")
    private String escalatedFlag;

    /** 供应商 8D 回复（SQE 代录） */
    @TableField("REPLY_TEXT")
    private String replyText;

    /** 回复录入人 */
    @TableField("REPLY_BY")
    private String replyBy;

    /** 回复时间 */
    @TableField("REPLY_DATE")
    private LocalDateTime replyDate;

    /** 退回次数（计供应商评分） */
    @TableField("REJECT_COUNT")
    private Integer rejectCount;

    /** 回复超期提醒计数（每 3 天一次，tasks 10.3） */
    @TableField("REPLY_REMIND_COUNT")
    private Integer replyRemindCount;

    /** 上次提醒时间（3 天周期基准） */
    @TableField("LAST_REMIND_TIME")
    private java.time.LocalDateTime lastRemindTime;

    /** 措施验证结论 */
    @TableField("VERIFY_RESULT")
    private String verifyResult;

    /** 验证人 */
    @TableField("VERIFY_BY")
    private String verifyBy;

    /** 验证时间 */
    @TableField("VERIFY_DATE")
    private LocalDateTime verifyDate;

    /** 关闭时间 */
    @TableField("CLOSED_DATE")
    private LocalDateTime closedDate;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}

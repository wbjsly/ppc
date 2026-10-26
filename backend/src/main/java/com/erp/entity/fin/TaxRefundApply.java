package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 增值税留抵退税申请（BR-4.14-33 / S-4.14-15，spec tax-filing）。
 * 四条件逐项校验（信用等级/连续6月增量留抵>0/第6月门槛/进项构成比例），
 * 允许退还 = 增量留抵 × 构成比例 × 退还比例；L2 强制审批；
 * 退回 → 结构化补正清单挂起；到账一致自动冲减留抵+收款凭证，差异>容差挂账核查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_refund_apply")
public class TaxRefundApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING_APPROVAL = "PENDING_APPROVAL";
    /** 受理（税务机关已受理） */
    public static final String ST_ACCEPTED = "ACCEPTED";
    /** 退回补正（挂起，补正后重提） */
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_PAID = "PAID";
    public static final String ST_REJECTED = "REJECTED";

    /** 申请单号 RVAP+yyyyMMdd+流水 */
    private String applyNo;
    private String period;
    /** 纳税信用等级 A/B/M/C/D */
    private String creditLevel;
    private BigDecimal incrCredit;
    /** 进项构成比例 0~1 */
    private BigDecimal constitutionRatio;
    /** 退还比例快照 */
    private BigDecimal refundRatio;
    private BigDecimal month6Incr;
    /** 连续 6 个月增量留抵快照 JSON */
    private String sixMonthJson;
    private BigDecimal allowedRefund;
    private String status;
    private String apprId;
    private String returnReason;
    /** 结构化补正任务清单 JSON（原因码/涉及票号/补正期限） */
    private String correctionsJson;
    private BigDecimal approvedAmount;
    private BigDecimal receivedAmount;
    private LocalDate receivedDate;
    private String voucherId;
    private String reconcileTaskId;
    private String remark;
}

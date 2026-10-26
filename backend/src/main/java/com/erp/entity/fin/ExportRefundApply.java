package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 出口退税申报单（FR-4.14-7-4/5，spec export-tax-refund，design D4）。
 * 状态机：DRAFT→SUBMITTED→ACCEPTED→AUDITING→APPROVED→PAID / RETURNED / REJECTED；
 * 退回 → 结构化补正清单 JSON 挂起；单证豁免走 L2（EXEMPT_FLAG+原因）；
 * 免抵退五值与同期 VAT 申报 line 勾稽（BR-4.14-44）；
 * 到账差异>容差挂应收出口退税核查不核销（BR-4.14-45）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_export_refund_apply")
public class ExportRefundApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_ACCEPTED = "ACCEPTED";
    public static final String ST_AUDITING = "AUDITING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_PAID = "PAID";
    /** 退回补正（挂起，暂停后续批次） */
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_REJECTED = "REJECTED";

    /** 退税申报单号 RFA+yyyyMMdd+流水 */
    private String applyNo;
    private String period;
    private Integer dealCount;
    private BigDecimal fobTotal;
    private BigDecimal refundTotal;
    private String status;
    private String apprId;
    private String exemptFlag;
    private String exemptReason;
    /** 退回补正清单 JSON（原因码/涉及报关单号/补正期限） */
    private String returnCorrectionsJson;
    private BigDecimal approvedAmount;
    private BigDecimal receivedAmount;
    private java.time.LocalDate receivedDate;
    private String voucherId;
    private String reconcileTaskId;
    // 免抵退汇总五值（BR-4.14-44）
    private BigDecimal mtdPayable;
    private BigDecimal mtdRefundTotal;
    private BigDecimal mtdRefundDue;
    private BigDecimal mtdExemptTax;
    private BigDecimal mtdEndCredit;
    /** 勾稽锚：同期增值税申报单 */
    private String vatReturnId;
    private String remark;
}

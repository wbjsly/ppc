package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 纳税申报单（M9-5-1/2，spec tax-filing，design D1）。
 * 增值税（TAX_TYPE=VAT）与企业所得税预缴（CIT）共表；状态机
 * DRAFT→PENDING_APPROVAL→APPROVED→SUBMITTED→FILED→PAID（REJECTED/OVERDUE）。
 * 勾稽（C-4.14-04）：申报数 vs erp_fin_balance POSTED 净发生额，差异>0 L1 阻断；
 * APPROVED 起 LOCK_FLAG=1，修改走跨期调整单（BR-4.14-31 快照）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_return")
public class TaxReturn extends BaseEntity {

    public static final String TYPE_VAT = "VAT";
    public static final String TYPE_CIT = "CIT";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_FILED = "FILED";
    public static final String ST_PAID = "PAID";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_OVERDUE = "OVERDUE";

    public static final String RECON_PENDING = "PENDING";
    public static final String RECON_PASSED = "PASSED";
    public static final String RECON_BLOCKED = "BLOCKED";

    /** 申报单号 TRET+yyyyMM+流水 */
    private String returnNo;
    /** 申报所属期 yyyyMM */
    private String period;
    private String taxType;
    private String status;

    // ---------- 增值税口径（BR-4.14-32 公式入参） ----------
    private BigDecimal outputTax;
    private BigDecimal inputTax;
    private BigDecimal transferOut;
    private BigDecimal prevCredit;
    private BigDecimal payableTax;
    private BigDecimal endCredit;

    // ---------- 所得税口径 ----------
    private BigDecimal citBase;
    private BigDecimal citAdjust;
    private BigDecimal citRate;
    private BigDecimal citCumPayable;
    private BigDecimal citPrepaid;
    private BigDecimal citDue;

    // ---------- 勾稽 ----------
    private String reconStatus;
    private BigDecimal reconDiffTotal;

    // ---------- 审批与锁定 ----------
    private String apprId;
    private String lockFlag;

    // ---------- 缴款回执（离线登记） ----------
    private LocalDate submitDate;
    private String receiptNo;
    private LocalDate fileDate;
    private LocalDate payDate;
    private BigDecimal payAmount;
    private String payVoucherNo;

    // ---------- 逾期事件单 ----------
    private String overdueFlag;
    private Integer overdueDays;
    private BigDecimal lateFee;
    private String faultNote;
    private String escalatedFlag;
    private String adjustedFlag;
    private String remark;
}

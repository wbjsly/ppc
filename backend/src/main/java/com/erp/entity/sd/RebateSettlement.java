package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返利结算单（计算 → 预算校验 → 审批 → 执行 → 凭证，tasks 11.3~11.8，
 * spec sales-rebate，FR-4.3-8-1~6、C-4.3-05）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_rebate_settlement")
public class RebateSettlement extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_EXECUTED = "EXECUTED";

    public static final String EXEC_OFFSET = "OFFSET";
    public static final String EXEC_CASH = "CASH";

    private String settleNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 如 2026Q4 */
    private String quarter;
    private BigDecimal targetAmt;
    /** 返利基数 = 开票确认额 − 退货退款额 */
    private BigDecimal baseAmt;
    /** 达成率（小数，0.95 = 95%） */
    private BigDecimal achieveRate;
    private BigDecimal rebateAmt;
    private BigDecimal budgetAmt;
    private BigDecimal budgetRemain;
    /** 1 = 超季度预算（C-4.3-05 升级总监） */
    private String overBudget;
    private String overReason;
    private String balancePlan;
    /** 超额累进分段明细 JSON */
    private String segments;
    private String status;
    private String approvalId;
    /** 1 = 数据不完整待补齐 */
    private String pendingData;
    private String pendingHint;
    private String execType;
    private LocalDateTime execAt;
    private String execBy;
    /** 客户确认方式留痕 */
    private String confirmBy;
    private String confirmNote;
    private BigDecimal offsetAmt;
    private BigDecimal cashAmt;
    private String voucherId;
    private String voucherNo;
    private String remark;
}

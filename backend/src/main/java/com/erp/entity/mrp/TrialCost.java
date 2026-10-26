package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试产成本表（change add-trial-production-management，spec trial-execution
 * FR-4.5-8-8 / BR-4.5-34 / BR-4.5-36；迁移 125）。
 * 归集行（MATERIAL/LABOR/OVERHEAD，REMARK=TRIAL: 前缀重建隔离）+ 冲回分录（REVERSAL，
 * 借存货/贷研发费用方向留痕，FIN_STATUS=PENDING_FINANCE → 财务确认 CONFIRMED）。
 * EXPENSE_TYPE=RD 不入标准成本卷积；PROJECT_NO 预留（4.8 项目域，偏差 D11）。
 */
@Getter
@Setter
@TableName("erp_mrp_trial_cost")
public class TrialCost extends BaseEntity {

    public static final String T_MATERIAL = "MATERIAL";
    public static final String T_LABOR = "LABOR";
    public static final String T_OVERHEAD = "OVERHEAD";
    public static final String T_REVERSAL = "REVERSAL";

    public static final String FIN_PENDING = "PENDING_FINANCE";
    public static final String FIN_CONFIRMED = "CONFIRMED";

    /** 归集重建摘要前缀（冲销回拨按前缀隔离重建） */
    public static final String REMARK_PREFIX = "TRIAL:";

    /** 成本行号 TC+yyyyMMdd+-+6 位流水 */
    private String entryNo;
    /** 试产工单 ID */
    private String moId;
    /** 试产工单号 TP-（冗余查询） */
    private String moNo;
    /** 成本类型 MATERIAL/LABOR/OVERHEAD/REVERSAL */
    private String costType;
    /** 金额（REVERSAL 按方向字段解读，AMOUNT 存绝对值入汇总） */
    private BigDecimal amount;
    /** 费用类型 RD=研发费用（BR-4.5-36 不入标准成本卷积） */
    private String expenseType;
    /** 项目号预留（NULL=归研发费用，偏差 D11） */
    private String projectNo;
    /** 借贷方向留痕（冲回：DR 存货/CR 研发费用，偏差 D3） */
    private String drCr;
    /** 关联批次（REVERSAL 转库存批次） */
    private String batchNo;
    /** 关联数量（REVERSAL 转库存数量） */
    private BigDecimal qty;
    /** 关联检验批 ID（REVERSAL 幂等锚点） */
    private String lotId;
    /** 财务确认状态（归集行直接 CONFIRMED，冲回行走确认） */
    private String finStatus;
    /** TrialCostReverse 审批实例 ID */
    private String approvalId;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String remark;
}

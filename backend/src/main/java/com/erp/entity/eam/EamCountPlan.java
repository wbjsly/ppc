package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 盘点计划（add-asset-count，design D1）：
 * FULL 全面 / SAMPLE 抽样（分类 RISK_LEVEL 比例，BR-26）；BASE_DATE 基准日快照锚（BR-29）；
 * 状态机 DRAFT→ISSUED→COUNTING→DIFF_REVIEW→ADJUSTING→CLOSED（覆盖率 100% 卡控 + 报告审批归档）。
 */
@Getter
@Setter
@TableName("erp_eam_count_plan")
public class EamCountPlan extends BaseEntity {

    /** CNT + yyyyMMdd + - + 4 位流水 */
    @TableField("CNT_NO")
    private String cntNo;

    /** FULL / SAMPLE */
    @TableField("COUNT_TYPE")
    private String countType;

    /** 盘点基准日（BR-29 快照锚） */
    @TableField("BASE_DATE")
    private LocalDate baseDate;

    /** SCAN 标签扫描 / MANUAL 手工对账 */
    @TableField("METHOD")
    private String method;

    @TableField("PLAN_DESC")
    private String planDesc;

    @TableField("START_DATE")
    private LocalDate startDate;

    @TableField("END_DATE")
    private LocalDate endDate;

    /** DRAFT→ISSUED→COUNTING→DIFF_REVIEW→ADJUSTING→CLOSED */
    @TableField("STATUS")
    private String status;

    @TableField("TOTAL_QTY")
    private Integer totalQty;

    @TableField("COUNTED_QTY")
    private Integer countedQty;

    /** 覆盖率（结案校验 BR-26 L1） */
    @TableField("COVERAGE_RATE")
    private java.math.BigDecimal coverageRate;

    /** NONE/PENDING/APPROVED（报告归档审批） */
    @TableField("REPORT_STATUS")
    private String reportStatus;

    @TableField("REPORT_APPR_ID")
    private String reportApprId;

    @TableField("REPORT_SUMMARY")
    private String reportSummary;

    @TableField("REJECT_REASON")
    private String rejectReason;

    @TableField("REMARK")
    private String remark;
}

package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统一工单（add-asset-maintenance，design D1/D3）：
 * PREVENTIVE 点检保养 / REPAIR 故障维修共用；状态机 PENDING→ASSIGNED→EXECUTING→PENDING_ACCEPT→CLOSED（+CANCELLED）；
 * 三构成成本（备件/人工/外协）；幂等键与来源类型为 4.11 IoT 联动预留。
 */
@Getter
@Setter
@TableName("erp_eam_work_order")
public class EamWorkOrder extends BaseEntity {

    /** WO + yyyyMMdd + - + 4 位流水 */
    @TableField("WO_NO")
    private String woNo;

    /** PREVENTIVE / REPAIR */
    @TableField("WO_TYPE")
    private String woType;

    /** PLAN / REPORT / IOT / MANUAL（IOT 仅预留） */
    @TableField("SRC_TYPE")
    private String srcType;

    /** 幂等键 设备编号+预警事件号 / 计划ID+计划日 */
    @TableField("IDEMPOTENT_KEY")
    private String idempotentKey;

    @TableField("PLAN_ID")
    private String planId;

    @TableField("PLAN_DATE")
    private LocalDate planDate;

    @TableField("REPORT_ID")
    private String reportId;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    /** 故障现象（REPAIR；BR-19 同现象统计键） */
    @TableField("FAULT_SYMPTOM")
    private String faultSymptom;

    @TableField("FAULT_TIME")
    private LocalDateTime faultTime;

    @TableField("IMPACT_SCOPE")
    private String impactScope;

    @TableField("PLAN_DESC")
    private String planDesc;

    /** SELF / OUTSOURCE */
    @TableField("RESOLVE_PLAN")
    private String resolvePlan;

    @TableField("OUTSOURCE_VENDOR")
    private String outsourceVendor;

    @TableField("OUTSOURCE_AMOUNT")
    private BigDecimal outsourceAmount;

    @TableField("OUTSOURCE_ACCEPT_FILE")
    private String outsourceAcceptFile;

    /** 备件清单快照 [{itemCode,itemName,qty}]（超领校验基准） */
    @TableField("BOM_JSON")
    private String bomJson;

    /** PENDING/ASSIGNED/EXECUTING/PENDING_ACCEPT/CLOSED/CANCELLED */
    @TableField("STATUS")
    private String status;

    @TableField("ASSIGNEE")
    private String assignee;

    @TableField("EST_HOURS")
    private BigDecimal estHours;

    @TableField("EXEC_DESC")
    private String execDesc;

    @TableField("ISSUE_DESC")
    private String issueDesc;

    @TableField("PARTS_CHANGED")
    private String partsChanged;

    @TableField("PARENT_WO_NO")
    private String parentWoNo;

    @TableField("ACCEPT_BY_OP")
    private String acceptByOp;

    @TableField("ACCEPT_BY_ENG")
    private String acceptByEng;

    @TableField("ACCEPT_DATE")
    private LocalDateTime acceptDate;

    /** GOOD/OK/BAD */
    @TableField("RESULT_EVAL")
    private String resultEval;

    @TableField("SPARE_COST")
    private BigDecimal spareCost;

    @TableField("LABOR_COST")
    private BigDecimal laborCost;

    /** 1=费率待调整（缺参） */
    @TableField("LABOR_RATE_PENDING")
    private String laborRatePending;

    @TableField("TOTAL_COST")
    private BigDecimal totalCost;

    /** BR-19 大修评估触发 */
    @TableField("OVERHAUL_ASSESS_FLAG")
    private String overhaulAssessFlag;

    /** PENDING_APPROVE/APPROVED/REJECTED（归档后解冻） */
    @TableField("ASSESS_STATUS")
    private String assessStatus;

    @TableField("CLOSE_BY")
    private String closeBy;

    @TableField("CLOSE_DATE")
    private LocalDateTime closeDate;

    @TableField("CANCEL_REASON")
    private String cancelReason;

    @TableField("REMARK")
    private String remark;
}

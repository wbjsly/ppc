package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 维护计划（add-asset-maintenance，design D1）：
 * 周期 D/W/M/Q/Y；关键设备须主管审批；批量生成工单幂等锚 = 计划ID+计划日。
 */
@Getter
@Setter
@TableName("erp_eam_maint_plan")
public class EamMaintPlan extends BaseEntity {

    /** MP + yyyyMMdd + - + 4 位流水 */
    @TableField("PLAN_NO")
    private String planNo;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    /** 维护项目 */
    @TableField("ITEM_NAME")
    private String itemName;

    /** D/W/M/Q/Y */
    @TableField("CYCLE")
    private String cycle;

    @TableField("STANDARD")
    private String standard;

    /** 所需备件清单 [{itemCode,itemName,qty}] */
    @TableField("SPARES_JSON")
    private String sparesJson;

    @TableField("EST_HOURS")
    private BigDecimal estHours;

    /** 1=关键设备（须主管审批） */
    @TableField("CRITICAL_FLAG")
    private String criticalFlag;

    /** NOT_REQUIRED/APPROVED/PENDING_APPROVE/REJECTED */
    @TableField("APPROVAL_STATUS")
    private String approvalStatus;

    @TableField("EFFECTIVE_FLAG")
    private String effectiveFlag;

    @TableField("NEXT_PLAN_DATE")
    private LocalDate nextPlanDate;

    @TableField("REMARK")
    private String remark;
}

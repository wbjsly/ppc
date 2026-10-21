package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报修单（add-asset-maintenance，design D1）：
 * 全员可提；安全风险自动挂锁；工程师确认后转 REPAIR 工单。
 */
@Getter
@Setter
@TableName("erp_eam_repair_report")
public class EamRepairReport extends BaseEntity {

    /** RB + yyyyMMdd + - + 4 位流水 */
    @TableField("REPORT_NO")
    private String reportNo;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    @TableField("FAULT_SYMPTOM")
    private String faultSymptom;

    @TableField("FAULT_TIME")
    private LocalDateTime faultTime;

    @TableField("IMPACT_SCOPE")
    private String impactScope;

    /** 1=涉及安全风险（提交时自动挂锁） */
    @TableField("SAFETY_RISK")
    private String safetyRisk;

    @TableField("DETAIL")
    private String detail;

    /** PENDING / CONVERTED / REJECTED */
    @TableField("STATUS")
    private String status;

    @TableField("WO_NO")
    private String woNo;

    @TableField("REJECT_REASON")
    private String rejectReason;

    @TableField("CONFIRM_BY")
    private String confirmBy;

    @TableField("CONFIRM_DATE")
    private LocalDateTime confirmDate;

    @TableField("REMARK")
    private String remark;
}

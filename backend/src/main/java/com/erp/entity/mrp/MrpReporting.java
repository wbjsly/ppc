package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报工单（change add-reporting-management，spec shop-floor-reporting「报工单生命周期」，
 * design D1，迁移 123）。一单一序：OPEN（可增改投料/产出/工时）→ CONFIRMED（只读，
 * BR-4.5-25 仅可冲销）→ REVERSED（冲销终态，REVERSES_ID 指向红字单）。
 * 工时分项在头（准备/加工/设备——FR-4.5-5-6，设备挂 WC 偏差 D4）。
 */
@Getter
@Setter
@TableName("erp_mrp_reporting")
public class MrpReporting extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_REVERSED = "REVERSED";

    /** 批量 / 逐件（BR-4.5-23） */
    public static final String MODE_BATCH = "BATCH";
    public static final String MODE_PIECE = "PIECE";

    /** 报工单号 RP+yyyyMMdd+流水 */
    private String rptNo;
    /** 来源派工单（FR-4.5-5-1 扫码定位） */
    private String dispatchId;
    private String dispatchNo;
    private String moId;
    private String moNo;
    private Integer opSeq;
    private String opName;
    /** 工作中心（设备运行时间挂点，偏差 D4） */
    private String wcCode;
    private String mode;
    private String status;
    /** 实际开工时间（FR-4.5-5-1） */
    private LocalDateTime startAt;
    /** 超期黄标 1：计划开工日早于当日放行（偏差 D2 不阻断） */
    private String overdueFlag;
    private String confirmBy;
    private LocalDateTime confirmAt;
    /** 红字冲销单指向原单（原单 REVERSED_BY_ID 记镜像单，BR-4.5-25） */
    private String reversesId;
    private String reversedById;
    private String reverseReason;
    private String reverseBy;
    private LocalDateTime reverseAt;
    /** 本单触发超产挂起 1（FR-4.5-5-4 分级） */
    private String overprodFlag;
    /** 实际准备工时（分项，FR-4.5-5-6） */
    private BigDecimal setupHours;
    /** 实际加工工时 */
    private BigDecimal runHours;
    /** 设备运行时间（挂 WC，偏差 D4/D11） */
    private BigDecimal machineHours;
    /** 工时异常待办已生成 1（WORKHOUR_DEVIATION_ALERT） */
    private String hourAlertFlag;
    private String hourConfirmBy;
    private LocalDateTime hourConfirmAt;
    /** 超产二次确认/审批已通过 1（阈值内操作工自确认） */
    private String prodConfirmFlag;
    /** 实际报工人（代报场景 = 操作账号） */
    private String reporter;
    /** 派工单确认人（人单不匹配判定基准，FR-4.5-5-1） */
    private String dispatchOwner;
    /** 代报标记 1（操作人 ≠ 派工确认人且确认继续） */
    private String proxyFlag;
    private String remark;
}

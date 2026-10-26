package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 派工单（change add-dispatch-management，spec dispatch-management「工序级派工单生成」，
 * design D1，迁移 122）。一工序一张（OP_SEQ 必填），状态机
 * PENDING_CONFIRM → RELEASED / CANCELLED（预留 5.7 扩展），外协回联三字段（PR/PO/收货）。
 */
@Getter
@Setter
@TableName("erp_mrp_dispatch")
public class MrpDispatch extends BaseEntity {

    public static final String ST_PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String ST_RELEASED = "RELEASED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 派工单号 DP+yyyyMMdd+流水 */
    private String dispatchNo;
    private String moId;
    /** 工单号（冗余查询） */
    private String moNo;
    /** 工序序号（工序级派工，BR-4.5-19 口径 D7） */
    private Integer opSeq;
    private String opName;
    private String wcCode;
    private String wcName;
    /** INTERNAL / OUTSOURCE */
    private String wcType;
    /** 排程计划开始（日期粒度，偏差 D5 仅前向） */
    private LocalDate planStart;
    /** 排程计划结束（超 planEndDate 置 RISK_FLAG） */
    private LocalDate planEnd;
    /** 占用工时 = setup + run */
    private BigDecimal durationHours;
    /** PENDING_CONFIRM / RELEASED / CANCELLED */
    private String status;
    /** 交期风险 1 */
    private String riskFlag;
    /** 缺料先行 1（C-4.5-12 审批通过置位） */
    private String shortageFirstFlag;
    /** 外协 1（BR-4.5-21） */
    private String outsourceFlag;
    private String confirmBy;
    private java.time.LocalDateTime confirmAt;
    private String cancelReason;
    /** 外协回联：请购单号 */
    private String prNo;
    /** 外协回联：采购订单号 */
    private String poNo;
    /** 外协回联：GR 收货完成 1 */
    private String receiptFlag;
    /** 外协收货累计（GR 过账回联递增；红冲递减；收足自动闭环依据，迁移 126） */
    private BigDecimal recvQty;
    /** 外协交期（供应商交付承诺日，5.10.1 工作台维护，迁移 126） */
    private LocalDate osDueDate;
    /** 外协备注（供料/工艺等供应商侧约定，迁移 126） */
    private String osRemark;
    /** 供料方式：SUPPLIER_BUY 供应商自购 / COMPANY_SUPPLY 甲供（迁移 126） */
    private String osSupplyType;
    /** 重排留痕等 */
    private String remark;
}

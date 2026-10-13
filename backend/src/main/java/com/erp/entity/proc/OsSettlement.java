package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外协对账单（change add-outsourcing-processing，spec outsourcing-settlement，迁移 126）：
 * 按外协 PO（派工单回联识别）汇总未对账收货过账明细，金额 = 数量 × PO 成交价（禁人工改价）。
 * 状态机：DRAFT（可改可删）→ CONFIRMED（审批中）→ POSTED（挂账生成，不可改）/ REJECTED（驳回回 DRAFT）。
 * 偏差 D-OS-02：采购域无 AP 模块，本单据为最小对账-挂账承接。
 */
@Getter
@Setter
@TableName("erp_proc_os_settlement")
public class OsSettlement extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_REJECTED = "REJECTED";

    /** 审批底座业务类型（单节点单签） */
    public static final String BIZ_TYPE = "OutsourceSettlement";

    /** 对账单号 OSR+yyyyMMdd+流水 */
    private String osrNo;
    /** 外协采购订单 */
    private String poNo;
    private String supplierCode;
    private String supplierName;
    /** 对账数量合计 */
    private BigDecimal totalQty;
    /** 对账金额合计（数量 × PO 成交价） */
    private BigDecimal totalAmt;
    /** DRAFT / CONFIRMED / POSTED / REJECTED */
    private String status;
    /** 审批底座实例号 */
    private String apprNo;
    private String postBy;
    /** 应付挂账生成时间 */
    private LocalDateTime postDate;
    private String rejectReason;
    private String remark;
}

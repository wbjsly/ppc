package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** VMI 结算单头（FR-4.2-8-4 / BR-4.2-38 双确认，design D7）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_settlement")
public class VmiSettlement extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_ON_HOLD = "ON_HOLD";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_INVOICED = "INVOICED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 结算单号 VS+yyyyMMdd+流水 */
    private String settleNo;
    private String supplierId;
    private String supplierName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String settleCycle;
    /** 逐笔汇总 = Σ(领用量 × 领用时点协议价) */
    private BigDecimal calcAmount;
    /** 期末价试算金额 */
    private BigDecimal trialAmount;
    /** 差额 = CALC − TRIAL */
    private BigDecimal diffAmount;
    /** 差率 = |差额| / TRIAL */
    private BigDecimal diffRate;
    /** DRAFT / ON_HOLD 超容差挂起 / CONFIRMED / INVOICED / CANCELLED */
    private String status;
    /** 采购员确认人（双确认之一） */
    private String pmConfirmBy;
    private LocalDateTime pmConfirmAt;
    /** 供应商确认人（线下确认登记，design D7） */
    private String supplierConfirmBy;
    private LocalDateTime supplierConfirmAt;
    /** 确认方式：邮件/传真/对账单回签等 */
    private String supplierConfirmWay;
    private String remark;
}

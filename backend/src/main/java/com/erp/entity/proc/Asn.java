package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ASN 发货通知（spec asn-collaboration，design D8/D9）：
 * 预生成收货预约 = 本单本身（状态 + 预计到货时间）；REPLENISH 来源可无 PO。
 */
@Getter
@Setter
@TableName("erp_proc_asn")
public class Asn extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_CLOSED = "CLOSED";

    public static final String SRC_PO = "PO";
    public static final String SRC_REPLENISH = "REPLENISH";

    public static final String OVER_NONE = "NONE";
    public static final String OVER_PENDING = "PENDING";
    public static final String OVER_RELEASED = "RELEASED";
    public static final String OVER_REJECTED = "REJECTED";

    /** ASN+yyyyMMdd+流水 */
    private String asnNo;
    /** 常规 ASN 必填（REPLENISH 可空，design D9） */
    private String poId;
    private String poNo;
    /** PO 常规 / REPLENISH 补货确认联动 */
    private String source;
    private String supplierId;
    private String supplierName;
    /** DRAFT / CONFIRMED 待到货 / CLOSED 已核销 */
    private String status;
    /** 超容差数量（C-4.9-07 待审批） */
    private BigDecimal overToleranceQty;
    /** NONE / PENDING 待审批 / RELEASED 已放行 / REJECTED 已驳回 */
    private String overStatus;
    /** 超收审批实例 ID */
    private String approvalId;
    /** 预计到货时间（收货预约视图） */
    private LocalDate expectArrival;
    private String logisticsNo;
    /** 实际到货 vs 承诺交期偏差小时（BR-4.9-01 绩效留痕） */
    private Integer arrivalDeviationHours;
    private String remark;
}

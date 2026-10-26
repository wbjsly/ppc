package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 付款计划单（add-ap-management design D4）。
 * PERIOD+LEGAL_ENTITY 唯一（同期间同法人一张计划）；PLAN_LINES 存分供应商计划行 JSON；
 * 超计划付款阻断锚（PaymentService.execute 前校验 EXECUTED_TOTAL + 本次 > PLAN_TOTAL）。
 */
@Getter
@Setter
@TableName("erp_fin_payment_plan")
public class ApPlan extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_CLOSED = "CLOSED";

    private String planNo;
    /** 会计期间 YYYYMM */
    private String period;
    /** 法人（可空=全法人；payment_request 无该维度故按期间匹配） */
    private String legalEntityId;
    /** 计划总额 */
    private BigDecimal planTotal;
    /** 已执行付款累计 */
    private BigDecimal executedTotal;
    /** 分供应商计划行 [{supplierId,supplierName,planAmount,executedAmount}...] */
    private String planLines;
    /** DRAFT/PENDING/APPROVED/CLOSED */
    private String status;
    /** 审批实例锚 */
    private String apprId;
    private String rejectReason;
    private String remark;
}

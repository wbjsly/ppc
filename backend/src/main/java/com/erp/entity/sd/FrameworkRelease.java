package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 框架下达单（框架订单，tasks 13.3/13.6）：引用协议行、客户、物料、数量与交期；
 * 发货后回写 SHIPPED_QTY 与状态（OPEN/PARTIAL/SHIPPED/CANCELLED）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_framework_release")
public class FrameworkRelease extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_PARTIAL = "PARTIAL";
    public static final String ST_SHIPPED = "SHIPPED";
    public static final String ST_CANCELLED = "CANCELLED";

    private String releaseNo;
    private String frameworkId;
    private String fwLineId;
    private Integer lineNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private BigDecimal shippedQty;
    /** 要求交期（分批执行时间表） */
    private LocalDate deliverDate;
    private String status;
    private String lastShipId;
    private String lastShipNo;
    private String remark;
}

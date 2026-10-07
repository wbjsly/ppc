package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售订单行。报价转化时由报价行带出（QUOTE_LINE_ID 关联）；
 * 已发量/预留等执行列在第 7/9 组补充。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_so_line")
public class SoLine extends BaseEntity {

    private String soId;
    private Integer lineNo;
    private String quoteLineId;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private String baseUnit;
    private BigDecimal unitPrice;
    private String priceSource;
    private String paCode;
    private BigDecimal amount;
    private LocalDate expectDeliveryDate;
    /** 发货仓库（BR-4.3-19 逐 SKU+仓库） */
    private String warehouseCode;
    /** 1 = 手动改价锁行，价格变更审批通过前禁止 SO 确认（BR-4.3-26） */
    private String priceLocked;
    /** 协议带出原价（改价对比） */
    private BigDecimal originalUnitPrice;
    /** 价格变更审批实例 */
    private String priceApprovalId;
    /** 计划发货日期 */
    private LocalDate planShipDate;
    /** 客户期望交期 */
    private LocalDate customerExpectDate;
    /** 1 = 交期待确认（计划晚于期望且客户未确认，BR-4.3-28） */
    private String deliveryPending;
    /** OPEN/PARTIAL/SHIPPED/INVOICED/CANCELLED（7.8 行级状态机） */
    private String lineStatus;
    private BigDecimal shippedQty;
    private BigDecimal invoicedQty;
    private BigDecimal reservedQty;
    private String remark;

    public static final String LS_OPEN = "OPEN";
    public static final String LS_CANCELLED = "CANCELLED";
}

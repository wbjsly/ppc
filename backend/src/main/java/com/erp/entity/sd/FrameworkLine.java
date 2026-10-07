package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 框架协议行（tasks 13.1，C-4.3-10 三量跟踪：
 * 剩余可下达 = TOTAL − RELEASED；剩余可发 = RELEASED − SHIPPED）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_framework_line")
public class FrameworkLine extends BaseEntity {

    private String frameworkId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private String baseUnit;
    /** 行协议总量 */
    private BigDecimal totalQty;
    /** 已下达量（13.3 回写） */
    private BigDecimal releasedQty;
    /** 已发量（13.6 过账回写） */
    private BigDecimal shippedQty;
    /** 锁定单价（下达/发货带出，13.5 单价重谈对象） */
    private BigDecimal unitPrice;
    private String warehouseCode;
    private String remark;
}

package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售报价明细行（FR-4.3-1-3/1-4/1-5）：
 * 行含取价结果与成本/MOQ 快照，行金额与毛利随报价头聚合。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_quote_line")
public class QuoteLine extends BaseEntity {

    private String quoteId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private String baseUnit;
    /** 期望交期（早于最早可交货日期 L4 提示） */
    private LocalDate expectDeliveryDate;
    /** 特殊包装要求 */
    private String specialPack;

    /** 含税单价（三类协议 trial 结果；无命中时创建被阻断） */
    private BigDecimal unitPrice;
    /** EXCLUSIVE/LADDER/TIME */
    private String priceSource;
    /** 命中协议编码 */
    private String paCode;
    private BigDecimal amount;
    /** 取价时点标准成本快照 */
    private BigDecimal standardCost;
    private BigDecimal lineCost;
    /** 取价时点 MOQ 快照 */
    private BigDecimal moq;
    private String remark;
}

package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 价格异动台账（spec procurement-cost-analysis：物料+月份唯一、处置状态机）。 */
@Getter
@Setter
@TableName("erp_bi_price_alert")
public class BiPriceAlert extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_HANDLED = "HANDLED";
    public static final String ST_IGNORED = "IGNORED";

    private String alertNo;
    private String monthTag;
    private String itemCode;
    private String supplierId;
    private String categoryCode;
    private String alertType;
    private BigDecimal pct;
    private BigDecimal thresholdPct;
    private BigDecimal prevPrice;
    private BigDecimal currPrice;
    /** EXTRACT 每日抽取 / MANUAL 实时补算 */
    private String source;
    private String status;
    private String handleBy;
    private LocalDateTime handleAt;
    private String handleNote;
    private String remark;
}

package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** VMI 告警/建议（WATER_HIGH 超水位确认放行 / REPLENISH 补货建议，design D3/D4）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_alert")
public class VmiAlert extends BaseEntity {

    public static final String TYPE_WATER_HIGH = "WATER_HIGH";
    public static final String TYPE_REPLENISH = "REPLENISH";
    public static final String ST_OPEN = "OPEN";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_RESOLVED = "RESOLVED";

    /** 告警号 VA+yyyyMMdd+流水 */
    private String alertNo;
    /** WATER_HIGH 超最高水位 / REPLENISH 低于最低水位 */
    private String alertType;
    private String agreeId;
    private String agreeNo;
    private String itemCode;
    private String itemName;
    private String supplierId;
    private String supplierName;
    /** 触发时寄售库存量 */
    private BigDecimal currentQty;
    /** 水位阈值（WATER_HIGH=MAX / REPLENISH=MIN） */
    private BigDecimal limitQty;
    /** OPEN / CONFIRMED 已确认放行 / RESOLVED 已处理 */
    private String status;
    /** 采购员确认人（BR-4.2-36 确认放行留痕） */
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String confirmOpinion;
    private String remark;
}

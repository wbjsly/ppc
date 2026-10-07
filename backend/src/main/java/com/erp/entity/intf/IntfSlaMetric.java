package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLA 指标（spec interface-sla-monitoring 7.1/7.2：DATA_MISSING 禁止填 0）。 */
@Getter
@Setter
@TableName("erp_intf_sla_metric")
public class IntfSlaMetric extends BaseEntity {

    public static final String KEY_AVAILABILITY = "AVAILABILITY";
    public static final String KEY_P95 = "P95";
    public static final String KEY_ERROR_RATE = "ERROR_RATE";
    public static final String KEY_E2E_DELAY = "E2E_DELAY";

    public static final String LVL_OK = "OK";
    public static final String LVL_WARNING = "WARNING";
    public static final String LVL_CRITICAL = "CRITICAL";
    public static final String LVL_EMERGENCY = "EMERGENCY";
    public static final String LVL_MISSING = "DATA_MISSING";

    private String metricKey;
    private String partnerCode;
    private String apiDomain;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private BigDecimal metricValue;
    private String unit;
    private BigDecimal targetValue;
    private String level;
    private String dataStatus;
    private Integer sampleCount;
    private String monthTag;
    private String remark;
}

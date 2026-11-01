package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * KPI 期间目标（add-kpi-dashboard，spec bi-kpi-dashboard）。
 * UNIQUE(KPI_KEY, PERIOD)；TARGET_MODE=PERIODIC 时按期取值。
 */
@Getter
@Setter
@TableName("erp_bi_kpi_target")
public class KpiTarget extends BaseEntity {

    private String kpiKey;
    /** yyyy-MM */
    private String period;
    private BigDecimal targetValue;
}

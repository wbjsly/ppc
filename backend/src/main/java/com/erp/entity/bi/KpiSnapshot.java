package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KPI 每日快照（add-kpi-dashboard，spec bi-kpi-dashboard）。
 * UNIQUE(KPI_KEY, DIM_VALUE, SNAP_DATE)——重跑幂等覆盖；DIM_VALUE 空串=全局。
 */
@Getter
@Setter
@TableName("erp_bi_kpi_snapshot")
public class KpiSnapshot extends BaseEntity {

    public static final String ST_ACHIEVED = "ACHIEVED";
    public static final String ST_WARN = "WARN";
    public static final String ST_BELOW = "BELOW";

    private String kpiKey;
    private String dimValue;
    private LocalDate snapDate;
    private BigDecimal actual;
    private BigDecimal target;
    private BigDecimal ratio;
    private String status;
    /** 取数失败留痕 */
    private String errorMsg;
}

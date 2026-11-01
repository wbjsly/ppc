package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * KPI 异常预警（add-kpi-dashboard，spec bi-kpi-dashboard）。
 * UNIQUE(KPI_KEY, DIM_VALUE)——ACTIVE 幂等 upsert；回达标置 RECOVERED。
 */
@Getter
@Setter
@TableName("erp_bi_kpi_alert")
public class KpiAlert extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_RECOVERED = "RECOVERED";

    private String kpiKey;
    private String dimValue;
    private String status;
    /** 连续低于目标天数 */
    private Integer daysBreached;
    private LocalDate firstBreachDate;
    private LocalDate recoverDate;
    /** 通知已发（防重） */
    private Boolean notifyFlag;
}

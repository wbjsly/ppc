package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运维容量指标日采集（18.4）。
 */
@Data
@TableName("erp_ops_capacity_metric")
public class OpsCapacityMetric implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String K_DB_SIZE = "DB_SIZE";
    public static final String K_CONN_POOL = "CONN_POOL";
    public static final String K_CPU = "CPU";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private LocalDate metricDate;

    /** DB_SIZE/CONN_POOL/CPU */
    private String metricKey;

    private BigDecimal metricValue;

    /** 连接池最大值等 */
    private BigDecimal maxValue;

    private String unit;

    private String createBy;

    private LocalDateTime createDate;
}

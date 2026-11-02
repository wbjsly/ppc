package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * IoT 测点时序（按月 RANGE 分区，原始 90 天/聚合 3 年保留策略）。
 * 分区键 TS 参与主键 → 复合主键 (ID, TS)，写入统一走 JdbcTemplate 批量插。
 */
@Data
@TableName("erp_iot_telemetry")
public class IotTelemetry implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String Q_GOOD = "GOOD";
    public static final String Q_SUSPECT = "SUSPECT";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 采样时间戳（分区键） */
    private LocalDateTime ts;

    private String deviceNo;

    private String pointCode;

    private BigDecimal val;

    /** GOOD/SUSPECT（物理合理范围外自动标记） */
    private String quality;

    /** 注入批次标记（演示/测试造数） */
    private String batchTag;
}

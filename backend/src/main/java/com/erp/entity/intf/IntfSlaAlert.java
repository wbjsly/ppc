package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLA 告警与升级（BR-4.9-27/28：5 分钟窗口唯一键去重 + 连续 Critical 升级链）。 */
@Getter
@Setter
@TableName("erp_intf_sla_alert")
public class IntfSlaAlert extends BaseEntity {

    public static final String LVL_WARNING = "WARNING";
    public static final String LVL_CRITICAL = "CRITICAL";
    public static final String LVL_EMERGENCY = "EMERGENCY";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_ACK = "ACK";
    public static final String ST_RESOLVED = "RESOLVED";

    /** 指标 + 伙伴（同键 5 分钟窗口唯一） */
    private String alertKey;
    private LocalDateTime alertWindow;
    private String metricKey;
    private String partnerCode;
    /** WARNING / CRITICAL / EMERGENCY */
    private String level;
    private BigDecimal actualValue;
    private BigDecimal thresholdValue;
    private String message;
    /** OPEN / ACK / RESOLVED */
    private String status;
    /** 连续 Critical 次数（≥3 升级） */
    private Integer critCount;
    /** 1 接口运维 / 2 运维主管 / 3 供应链协同管理员 */
    private Integer escalationLevel;
    private String escalatedTo;
    private LocalDateTime responseDueAt;
    private String respondedBy;
    private LocalDateTime respondedAt;
    private LocalDateTime alertAt;
    private String remark;
}

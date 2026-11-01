package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运维告警事件（18.2 一等公民：风暴聚合/P1 豁免/升级状态机载体）。
 */
@Data
@TableName("erp_ops_alert")
public class OpsAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String LV_P1 = "P1";
    public static final String LV_P2 = "P2";
    public static final String LV_P3 = "P3";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CLAIMED = "CLAIMED";
    public static final String ST_ESCALATED = "ESCALATED";
    public static final String ST_RESOLVED = "RESOLVED";
    public static final String ST_SUPPRESSED = "SUPPRESSED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String alertNo;

    /** P1/P2/P3 */
    private String level;

    /** sync-delay/capacity/change/manual */
    private String source;

    private String eventType;

    private String title;

    private String detail;

    private BigDecimal metricValue;

    private BigDecimal threshold;

    /** 风暴聚合根因告警ID */
    private String rootCauseId;

    private String suppressed;

    private String suppressWindow;

    /** OPEN/CLAIMED/ESCALATED/RESOLVED/SUPPRESSED */
    private String status;

    /** 0未升级/1L1/2L2/3L3 */
    private Integer escalationLevel;

    private String handler;

    private String incidentId;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}

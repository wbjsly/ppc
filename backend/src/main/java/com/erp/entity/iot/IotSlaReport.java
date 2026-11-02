package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 移动/IoT SLA 月度报告（13.9 复用 OpsSlaReport 范式） */
@Data
@TableName("erp_iot_sla_report")
public class IotSlaReport implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_ARCHIVED = "ARCHIVED";
    public static final String ST_PUBLISHED = "PUBLISHED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** yyyy-MM */
    private String period;

    private String status;

    /** 五指标快照 JSON：可用率/同步成功率/响应时间/离线占比/告警响应 */
    private String metricsJson;

    private LocalDateTime publishAt;

    /** 未归档发布阻断尝试留痕 */
    private String publishLog;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}

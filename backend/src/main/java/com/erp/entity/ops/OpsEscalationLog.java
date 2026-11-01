package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 升级日志（18.3）。
 */
@Data
@TableName("erp_ops_escalation_log")
public class OpsEscalationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String alertId;

    private String incidentId;

    private String fromLevel;

    private String toLevel;

    private String reason;

    private String notifyChannel;

    private LocalDateTime escalateAt;

    private String createBy;

    private LocalDateTime createDate;
}

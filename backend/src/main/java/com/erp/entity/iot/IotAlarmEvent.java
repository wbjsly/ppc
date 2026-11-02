package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** IoT 告警事件（多级阈值/5min 合并/确认-处理-关闭闭环） */
@Data
@TableName("erp_iot_alarm_event")
public class IotAlarmEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String LV_L1 = "L1";
    public static final String LV_L2 = "L2";
    public static final String LV_L3 = "L3";

    public static final String TYPE_THRESHOLD = "THRESHOLD";
    public static final String TYPE_TREND = "TREND";
    public static final String TYPE_OFFLINE = "OFFLINE";
    public static final String TYPE_EXCEPTION = "EXCEPTION";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_CLOSED = "CLOSED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String eventNo;

    private String deviceNo;

    /** L1 停机/L2 冻结/L3 日志 */
    private String alertLevel;

    /** THRESHOLD/TREND/OFFLINE/EXCEPTION */
    private String alertType;

    private String pointCode;

    private String ruleSnap;

    /** 合并键 设备+规则+级别（5min 窗口） */
    private String mergeKey;

    private Integer mergeCnt;

    private String status;

    private String confirmBy;
    private LocalDateTime confirmAt;
    private String closeBy;
    private LocalDateTime closeAt;

    private String escalateTo;
    private LocalDateTime escalateAt;

    /** 预测维护建单指向（幂等键=事件 ID） */
    private String linkWoId;

    /** 质量冻结单指向（L2 联动） */
    private String linkFreezeId;

    /** L1 停机指令下发打点（仿真通道） */
    private LocalDateTime stopCmdAt;

    private Integer stopAckMs;

    /** 误报标记 */
    private String falsePositive;

    private LocalDateTime occurredAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}

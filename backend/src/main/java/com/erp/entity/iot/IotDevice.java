package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** IoT 设备接入档案（FR-4.11-5-1 设备注册与协议配置） */
@Data
@TableName("erp_iot_device")
public class IotDevice implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_ONLINE = "ONLINE";
    public static final String ST_OFFLINE = "OFFLINE";
    public static final String ST_FAULT = "FAULT";
    public static final String ST_MAINT = "MAINT";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 设备编号（唯一，关联物理资产台账） */
    private String deviceNo;

    /** SENSOR/RFID/PLC_GATEWAY */
    private String deviceType;

    private String deviceName;

    /** MQTT/OPC-UA/MODBUS_TCP/MODBUS_RTU */
    private String protocol;

    /** 优先级 OPC-UA=1 > MQTT=2 > Modbus TCP=3 > RTU=4 */
    private Integer protocolPriority;

    /** 采集频率秒（关键设备 1，一般 60） */
    private Integer sampleFreqSec;

    /** 数据点映射 JSON */
    private String pointMapJson;

    /** 多级阈值规则 JSON */
    private String alertRuleJson;

    /** ONLINE/OFFLINE/FAULT/MAINT */
    private String status;

    private LocalDateTime heartbeatAt;

    private String certSn;

    /** ACTIVE/REVOKED（无效 L1 拒绝接入） */
    private String certStatus;

    /** 关联 EAM 物理资产编号 */
    private String assetNo;

    private String workArea;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}

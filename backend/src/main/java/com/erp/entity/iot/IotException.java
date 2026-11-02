package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 移动端异常上报单（13.4 三类 + 联动 + 同班次合并） */
@Data
@TableName("erp_iot_exception")
public class IotException implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_QUALITY = "QUALITY";
    public static final String TYPE_EQUIPMENT = "EQUIPMENT";
    public static final String TYPE_SAFETY = "SAFETY";

    public static final String SEV_GENERAL = "GENERAL";
    public static final String SEV_SEVERE = "SEVERE";
    public static final String SEV_URGENT = "URGENT";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_CLOSED = "CLOSED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String excNo;

    /** QUALITY/EQUIPMENT/SAFETY */
    private String excType;

    /** GENERAL/SEVERE/URGENT */
    private String severity;

    private String title;
    private String description;

    private String deviceNo;
    private String moNo;
    private String itemCode;
    private String station;

    /** 照片/视频附件 JSON */
    private String photoUrls;

    /** MANUAL 人工/IOT 传感器自动 */
    private String source;

    /** IoT 自动上报现场确认门禁 */
    private String confirmFlag;

    private String status;

    /** 设备故障→EAM 维修工单 */
    private String linkWoId;

    /** 质量异常→质量冻结单 */
    private String linkFreezeId;

    /** 安全事件停机指令打点（仿真通道） */
    private LocalDateTime stopCmdAt;
    private Integer stopAckMs;

    /** 同班次≥3 合并指向首单 */
    private String mergeOf;
    private Integer mergeCnt;

    /** 30min 响应计时 */
    private LocalDateTime dueAt;
    private LocalDateTime closedAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}

package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 离线同步队列（13.5，幂等键防重） */
@Data
@TableName("erp_iot_offline_queue")
public class IotOfflineQueue implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_PENDING = "PENDING";
    public static final String ST_SYNCED = "SYNCED";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_CONFLICT = "CONFLICT";

    /** BIZ_TYPE */
    public static final String BIZ_REPORT = "REPORT";
    public static final String BIZ_SCAN = "SCAN";
    public static final String BIZ_INSPECT = "INSPECT";
    public static final String BIZ_EXCEPTION = "EXCEPTION";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 业务幂等键 工单号+工序+时间戳+设备ID */
    private String idempotentKey;

    private String deviceCode;

    /** REPORT/SCAN/INSPECT/EXCEPTION */
    private String bizType;

    /** 业务载荷 JSON */
    private String payloadJson;

    /** 客户端版本号（冲突三重检测） */
    private Integer clientVerNo;

    /** 客户端操作时间戳 */
    private LocalDateTime clientTs;

    /** PENDING/SYNCED/FAILED/CONFLICT */
    private String status;

    private Integer retryCnt;

    private LocalDateTime syncedAt;

    private String errMsg;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}

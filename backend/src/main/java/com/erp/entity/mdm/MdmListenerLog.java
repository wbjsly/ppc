package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MDM 下游监听执行台账（1.8.2 内部 Tab 数据源）。
 */
@Data
@TableName("erp_mdm_listener_log")
public class MdmListenerLog implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_SUCCESS = "SUCCESS";
    public static final String ST_RETRYING = "RETRYING";
    public static final String ST_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 内部事件 UUID（与 outbox EVENT_ID 对齐） */
    private String eventId;

    /** MDM.ITEM.UPDATED 等 */
    private String eventType;

    private String entityId;

    /** priceRecalcMark / creditRefresh / taxSyncHint */
    private String listenerName;

    /** SUCCESS / RETRYING / FAILED */
    private String status;

    private Integer retryCnt;

    private Integer execMs;

    private String errorMsg;

    /** 3 次失败关联 erp_intf_ticket.ID */
    private String workTicketId;

    private String createBy;

    private LocalDateTime createDate;
}

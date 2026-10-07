package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 人工处理工单（C-4.9-02）：事件死信 / 报文待人工 / 幂等冲突 / 版本不兼容共用。
 * 工单 MUST 保留原幂等键，人工确认后方可重放。
 */
@Getter
@Setter
@TableName("erp_intf_ticket")
public class IntfTicket extends BaseEntity {

    public static final String SRC_EVENT_DEAD = "EVENT_DEAD";
    public static final String SRC_EDI_MANUAL = "EDI_MANUAL";
    public static final String SRC_CONFLICT = "CONFLICT";
    public static final String SRC_VERSION = "VERSION";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_DONE = "DONE";
    public static final String ST_CLOSED = "CLOSED";

    /** IT+yyyyMMdd+000001 */
    private String ticketNo;
    private String source;
    /** 投递 ID / 报文 ID / 契约 ID */
    private String refId;
    private String partnerCode;
    private String idempotencyKey;
    private String title;
    private String errorLoc;
    private String detail;
    private String priority;
    private String status;
    private String assignTo;
    private LocalDateTime dueAt;
    private String handledBy;
    private LocalDateTime handledAt;
    private String handlerNote;
    private String remark;
}

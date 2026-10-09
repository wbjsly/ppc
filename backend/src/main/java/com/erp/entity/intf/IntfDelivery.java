package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事件投递台账（spec interface-event-delivery，design D3）。
 * 至少一次投递：PENDING → DELIVERING → DELIVERED；失败 RETRYING（指数退避）→ 超 API_RETRY_MAX 转 DEAD。
 */
@Getter
@Setter
@TableName("erp_intf_delivery")
public class IntfDelivery extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_DELIVERING = "DELIVERING";
    public static final String ST_DELIVERED = "DELIVERED";
    public static final String ST_RETRYING = "RETRYING";
    public static final String ST_DEAD = "DEAD";
    /** 本地暂存（总线/回调不可达，BR-4.9-16） */
    public static final String ST_STAGED = "STAGED";
    /** 人工判定放弃 */
    public static final String ST_ABANDONED = "ABANDONED";
    /** 并发冲突落败（BR-4.9-13 冲突丢弃留痕） */
    public static final String ST_CONFLICT_DROPPED = "CONFLICT_DROPPED";

    private String eventId;
    private String eventType;
    /** 重放 MUST 复用原幂等键（C-4.9-04） */
    private String idempotencyKey;
    private String bizCode;
    private String partnerCode;
    private String status;
    private Integer retryCount;
    /** 下次投递时间（指数退避 1s→2s→4s…） */
    private LocalDateTime nextAt;
    private LocalDateTime lastAt;
    private LocalDateTime ackAt;
    private Integer costMs;
    private String failReason;
    private Integer replayCount;
    private String replayBy;
    private String ticketId;
    private String remark;
}

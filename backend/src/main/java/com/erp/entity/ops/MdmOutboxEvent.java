package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通用事务性发件箱事件（7.2 事件契约，表 erp_ops_outbox，ops 域）。
 * 信封字段与规格一致；IDEMPOTENCY_KEY 唯一（C-0-06 幂等），STATUS 恒 PENDING（消息总线未接入桩口径）。
 */
@Data
@TableName("erp_ops_outbox")
public class MdmOutboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 事件唯一标识（UUID） */
    private String eventId;

    /** MDM.CUSTOMER.CREATED 等事件类型 */
    private String eventType;

    /** 事件 Schema 版本 */
    private Integer version;

    /** 业务记录版本（record_version，与 Schema 版本正交） */
    private Integer recordVersion;

    private LocalDateTime occurredAt;

    /** 生产方（mdm-service） */
    private String source;

    /** 幂等键：业务编码:vN（唯一索引，重放拒绝） */
    private String idempotencyKey;

    private String legalEntityId;

    /** 变更后关键字段 JSON（按事件类型白名单，不含敏感字段） */
    private String payload;

    /** PENDING / DELIVERED / DEAD（当前恒 PENDING，投递桩） */
    private String status;

    private String consumerNote;

    private String createBy;

    private LocalDateTime createDate;
}

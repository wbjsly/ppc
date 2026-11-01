package com.erp.mdm.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MDM 变更内部事件（1.8 集成同步）：13 类主数据 × CREATED/UPDATED/DISABLED/MERGED 全操作广播。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MdmChangedEvent {

    /** 内部事件 UUID（与 outbox EVENT_ID 对齐） */
    private String eventId;

    /** MDM.ITEM.UPDATED 等 */
    private String eventType;

    /** ITEM / SUPPLIER / CUSTOMER_VIEW ... */
    private String bizType;

    private String entityId;

    private int recordVersion;

    private String operator;

    /** 变更字段清单（差异同步用） */
    private List<String> changedFields;

    /** 回滚标记 */
    private boolean rollback;
}

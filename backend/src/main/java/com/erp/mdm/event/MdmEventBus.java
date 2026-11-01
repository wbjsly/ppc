package com.erp.mdm.event;

import com.erp.ops.OutboxPublisher;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MDM 事件总线：内部广播（Spring Event）+ 外部发件箱（OutboxPublisher）同源双路。
 * 广播/落箱失败 MUST NOT 阻断主数据业务主流程（mdm-event-sync spec）。
 */
@Slf4j
@Component
public class MdmEventBus {

    private final ApplicationEventPublisher publisher;
    private final OutboxPublisher outbox;

    public MdmEventBus(ApplicationEventPublisher publisher, OutboxPublisher outbox) {
        this.publisher = publisher;
        this.outbox = outbox;
    }

    /**
     * 发布变更事件：先内部广播（缓存失效+监听器），再写发件箱（外部投递）。
     * 任一环节失败仅记 warn，不抛出。
     */
    public void publish(String eventType, String bizType, String entityId,
                        int recordVersion, List<String> changedFields, boolean rollback) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String operator = safeOperator();
        try {
            publisher.publishEvent(new MdmChangedEvent(eventId, eventType, bizType, entityId,
                    recordVersion, operator, changedFields, rollback));
        } catch (Exception e) {
            log.warn("mdm internal event broadcast failed (non-blocking): {} {}", eventType, e.getMessage());
        }
        try {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("eventId", eventId);
            extra.put("bizType", bizType);
            extra.put("entityId", entityId);
            if (changedFields != null && !changedFields.isEmpty()) {
                extra.put("changedFields", String.join(",", changedFields));
            }
            if (rollback) {
                extra.put("rollback", "true");
            }
            outbox.publishEvent(eventType, bizType + ":" + entityId, recordVersion,
                    null, rollback ? "回滚生成新版本 V" + recordVersion : null, extra);
        } catch (Exception e) {
            log.warn("mdm outbox publish failed (non-blocking): {} {}", eventType, e.getMessage());
        }
    }

    private String safeOperator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}

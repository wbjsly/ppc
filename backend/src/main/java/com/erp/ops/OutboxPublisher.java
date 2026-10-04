package com.erp.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.entity.ops.MdmOutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 事务性发件箱发布器（7.2 事件契约 + Outbox 模式）。
 * 须在 @Transactional 方法内调用：与主操作同事务，插入失败 → 主操作回滚（原子性）。
 * 幂等键 bizCode:vN 唯一索引冲突 → 409 fail-fast（同版本重复 = 快照 bug 信号，C-0-06）。
 */
@Slf4j
@Component
public class OutboxPublisher {

    /** payload 白名单按事件类型裁剪（最小披露：不含税号/电话/地址） */
    private static final Set<String> CODE_FOCUS = Set.of(
            "MDM.CUSTOMER.CREATED", "MDM.CUSTOMER.DISABLED", "MDM.CUSTOMER.ENABLED",
            "MDM.CUSTOMER.FROZEN", "MDM.CUSTOMER.UNFROZEN", "MDM.CUSTOMER.MERGED");
    private static final Set<String> CREDIT_FOCUS = Set.of(
            "MDM.CUSTOMER.CREDIT_UPDATED", "MDM.CUSTOMER.REVIEWED", "MDM.CUSTOMER.TEMP_ROLLBACK");

    private final MdmOutboxDao outboxDao;

    public OutboxPublisher(MdmOutboxDao outboxDao) {
        this.outboxDao = outboxDao;
    }

    /**
     * @param eventType     MDM.CUSTOMER.* 十类之一
     * @param bizCode       业务编码（客户编码/法人视图 ID，用于幂等键与事件流检索）
     * @param recordVersion 业务记录版本（快照后 verNo）
     * @param legalEntityId 关联法人（可空）
     * @param diffSummary   变更摘要（写入 payload.diff）
     */
    public void publish(String eventType, String bizCode, int recordVersion,
                        String legalEntityId, String diffSummary) {
        publishEvent(eventType, bizCode, recordVersion, legalEntityId, diffSummary, null);
    }

    /** 含附加 payload 字段（如建档的 customerName/taxNo，内部台账用途） */
    public void publishEvent(String eventType, String bizCode, int recordVersion,
                             String legalEntityId, String diffSummary,
                             Map<String, Object> extra) {
        MdmOutboxEvent e = new MdmOutboxEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setEventType(eventType);
        e.setVersion(1);
        e.setRecordVersion(recordVersion);
        e.setOccurredAt(LocalDateTime.now());
        e.setSource("mdm-service");
        e.setIdempotencyKey(bizCode + ":v" + recordVersion);
        e.setLegalEntityId(legalEntityId);
        e.setPayload(buildPayload(eventType, bizCode, recordVersion, diffSummary, extra));
        e.setStatus("PENDING"); // 消息总线未接入，PENDING 为预期态（桩口径）
        e.setConsumerNote("消息总线未接入，PENDING 为预期态");
        try {
            outboxDao.insert(e);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ServiceException(409, "事件幂等键重复（" + e.getIdempotencyKey()
                    + "），同版本重复发布被拒绝（C-0-06）");
        }
        // 日志与台账双写（spec：log 桩保留）
        log.info("{} code={} v{} key={}", eventType, bizCode, recordVersion, e.getIdempotencyKey());
    }

    private String buildPayload(String eventType, String bizCode, int recordVersion,
                                String diffSummary, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bizCode", bizCode);
        p.put("recordVersion", recordVersion);
        if (diffSummary != null && !diffSummary.isEmpty()) {
            p.put("diff", diffSummary);
        }
        // 事件类型→字段白名单（D2）：信用类才带额度语义标记，避免误导消费端
        if (CREDIT_FOCUS.contains(eventType)) {
            p.put("creditSemantic", true);
        }
        if (CODE_FOCUS.contains(eventType) && diffSummary == null) {
            p.put("lifecycle", eventType.substring("MDM.CUSTOMER.".length()));
        }
        if (extra != null) {
            p.putAll(extra);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ServiceException(500, "事件 payload 序列化失败");
        }
    }
}

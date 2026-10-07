package com.erp.service.impl.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.entity.ops.MdmOutboxEvent;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.ops.OutboxPublisher;
import com.erp.ops.PortalEvents;
import com.erp.service.portal.PortalEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门户推送事件实现（spec portal-event-push，design D10）。
 * source=portal-service；载荷最小披露（仅业务标识与状态，无敏感字段）。
 */
@Slf4j
@Service
public class PortalEventServiceImpl implements PortalEventService {

    private static final String SRC = "portal-service";

    private final OutboxPublisher outbox;
    private final MdmOutboxDao outboxDao;

    public PortalEventServiceImpl(OutboxPublisher outbox, MdmOutboxDao outboxDao) {
        this.outbox = outbox;
        this.outboxDao = outboxDao;
    }

    @Override
    public void poPushed(PurchaseOrder po) {
        Map<String, Object> extra = base(po.getSupplierId());
        extra.put("poNo", po.getPoNo());
        extra.put("poType", po.getPoType());
        extra.put("totalAmt", po.getTotalAmt());
        outbox.publishSourced(PortalEvents.PO_PUSHED, PortalEvents.poPushedKey(po.getPoNo(), po.getCurrVersion()),
                Math.max(1, po.getCurrVersion()), null,
                "PO 下达推送 v" + po.getCurrVersion(), extra, SRC);
    }

    @Override
    public void poConfirmed(String poNo, int confirmSeq, String supplierId, String promiseDate, String source) {
        Map<String, Object> extra = base(supplierId);
        extra.put("poNo", poNo);
        extra.put("promiseDate", promiseDate);
        extra.put("confirmSource", source);
        outbox.publishSourced(PortalEvents.PO_CONFIRMED, PortalEvents.poConfirmedKey(poNo),
                Math.max(1, confirmSeq), null, "交期确认（" + source + "）", extra, SRC);
    }

    @Override
    public void asnCreated(Asn asn) {
        Map<String, Object> extra = base(asn.getSupplierId());
        extra.put("asnNo", asn.getAsnNo());
        extra.put("poNo", asn.getPoNo());
        extra.put("source", asn.getSource());
        extra.put("expectArrival", asn.getExpectArrival() == null ? null : asn.getExpectArrival().toString());
        outbox.publishSourced(PortalEvents.ASN_CREATED, PortalEvents.asnCreatedKey(asn.getAsnNo()),
                1, null, "ASN 创建", extra, SRC);
    }

    @Override
    public boolean waterSynced(String supplierId, String snapshotHash, List<Map<String, Object>> snapshotRows) {
        LocalDateTime now = LocalDateTime.now();
        String key = PortalEvents.waterSyncedKey(supplierId, now);
        // 同小时桶已存在 → 跳过（不重复生成批次）
        Long sameBucket = outboxDao.selectCount(new LambdaQueryWrapper<MdmOutboxEvent>()
                .eq(MdmOutboxEvent::getEventType, PortalEvents.WATER_SYNCED)
                .likeRight(MdmOutboxEvent::getIdempotencyKey, key + ":v"));
        if (sameBucket != null && sameBucket > 0) {
            return false;
        }
        // 快照哈希与最近一次相同 → 无变化不写（design D10）
        MdmOutboxEvent last = outboxDao.selectOne(new LambdaQueryWrapper<MdmOutboxEvent>()
                .eq(MdmOutboxEvent::getEventType, PortalEvents.WATER_SYNCED)
                .likeRight(MdmOutboxEvent::getIdempotencyKey, "WATER:" + supplierId + ":H")
                .orderByDesc(MdmOutboxEvent::getOccurredAt)
                .last("LIMIT 1"));
        if (last != null && last.getPayload() != null
                && last.getPayload().contains("\"" + snapshotHash + "\"")) {
            return false;
        }
        Map<String, Object> extra = base(supplierId);
        extra.put("syncTime", now.toString());
        extra.put("items", snapshotRows);
        outbox.publishSourced(PortalEvents.WATER_SYNCED, key, 1, null, snapshotHash, extra, SRC);
        return true;
    }

    @Override
    public void replenishPushed(String alertId, String supplierId, String itemCode, BigDecimal suggestQty) {
        Map<String, Object> extra = base(supplierId);
        extra.put("alertId", alertId);
        extra.put("itemCode", itemCode);
        extra.put("suggestQty", suggestQty);
        outbox.publishSourced(PortalEvents.REPLENISH_PUSHED, PortalEvents.replenishPushedKey(alertId),
                1, null, "补货建议推送", extra, SRC);
    }

    @Override
    public void replenishConfirmed(String alertId, String supplierId, String itemCode,
                                   BigDecimal confirmQty, String asnNo) {
        Map<String, Object> extra = base(supplierId);
        extra.put("alertId", alertId);
        extra.put("itemCode", itemCode);
        extra.put("confirmQty", confirmQty);
        extra.put("asnNo", asnNo);
        outbox.publishSourced(PortalEvents.REPLENISH_PUSHED, PortalEvents.replenishConfirmedKey(alertId),
                1, null, "补货建议确认 → ASN " + asnNo, extra, SRC);
    }

    @Override
    public void settlePushed(String settleNo, int seq, String supplierId, BigDecimal amount, String period) {
        Map<String, Object> extra = base(supplierId);
        extra.put("settleNo", settleNo);
        extra.put("amount", amount);
        extra.put("period", period);
        outbox.publishSourced(PortalEvents.SETTLE_PUSHED, PortalEvents.settlePushedKey(settleNo),
                Math.max(1, seq), null, "结算单推送", extra, SRC);
    }

    @Override
    public Page<Map<String, Object>> ledger(String eventType, String supplierId, String keyword,
                                            String from, String to, long current, long size) {
        LambdaQueryWrapper<MdmOutboxEvent> qw = new LambdaQueryWrapper<MdmOutboxEvent>()
                .orderByDesc(MdmOutboxEvent::getOccurredAt);
        if (StringUtils.hasText(eventType)) {
            qw.eq(MdmOutboxEvent::getEventType, eventType);
        } else {
            qw.in(MdmOutboxEvent::getEventType, List.of(PortalEvents.PO_PUSHED, PortalEvents.PO_CONFIRMED,
                    PortalEvents.ASN_CREATED, PortalEvents.WATER_SYNCED,
                    PortalEvents.REPLENISH_PUSHED, PortalEvents.SETTLE_PUSHED));
        }
        if (StringUtils.hasText(from)) {
            qw.ge(MdmOutboxEvent::getOccurredAt, parseTime(from, true));
        }
        if (StringUtils.hasText(to)) {
            qw.le(MdmOutboxEvent::getOccurredAt, parseTime(to, false));
        }
        if (StringUtils.hasText(supplierId)) {
            qw.like(MdmOutboxEvent::getPayload, "\"supplierId\":\"" + supplierId.trim() + "\"");
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(MdmOutboxEvent::getPayload, keyword.trim());
        }
        Page<MdmOutboxEvent> page = outboxDao.selectPage(new Page<>(current, size), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MdmOutboxEvent e : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventType", e.getEventType());
            m.put("idempotencyKey", e.getIdempotencyKey());
            m.put("bizCode", e.getIdempotencyKey().contains(":v")
                    ? e.getIdempotencyKey().substring(0, e.getIdempotencyKey().lastIndexOf(":v"))
                    : e.getIdempotencyKey());
            m.put("status", e.getStatus());
            m.put("occurredAt", e.getOccurredAt());
            m.put("summary", e.getPayload() == null ? null
                    : (e.getPayload().length() > 300 ? e.getPayload().substring(0, 300) : e.getPayload()));
            rows.add(m);
        }
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(rows);
        return out;
    }

    /** 兼容 yyyy-MM-dd（起/止日边界）与 yyyy-MM-dd HH:mm[:ss] */
    private static LocalDateTime parseTime(String s, boolean startOfDay) {
        String t = s.trim();
        if (t.length() == 10) {
            return java.time.LocalDate.parse(t).atTime(startOfDay ? 0 : 23,
                    startOfDay ? 0 : 59, startOfDay ? 0 : 59);
        }
        return LocalDateTime.parse(t.replace(' ', 'T'));
    }

    private Map<String, Object> base(String supplierId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", supplierId);
        return m;
    }
}

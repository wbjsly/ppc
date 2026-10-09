package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfDeliveryDao;
import com.erp.dao.intf.IntfTicketDao;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfDelivery;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfTicket;
import com.erp.entity.ops.MdmOutboxEvent;
import com.erp.security.IntfGuard;
import com.erp.service.intf.DeliveryService;
import com.erp.service.intf.OpenApiGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 出站事件投递（spec interface-event-delivery，design D3）。
 * 投递目标为伙伴通道的回调地址；无回调地址时按台账确认（等价于"已同步给内部消费方"）。
 * 失败按指数退避重试，超过 API_RETRY_MAX 转死信并自动生成保留原幂等键的工单。
 */
@Slf4j
@Service
public class DeliveryServiceImpl implements DeliveryService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 退避序列 1s→2s→4s→8s→16s→32s */
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 32};

    private final MdmOutboxDao outboxDao;
    private final IntfDeliveryDao deliveryDao;
    private final IntfChannelDao channelDao;
    private final IntfTicketDao ticketDao;
    private final OpenApiGatewayService gateway;

    @Value("${app.intf.api-retry-max:3}") private int retryMax;
    @Value("${app.intf.staged-queue-limit:10000}") private int stagedLimit;
    @Value("${app.intf.dead-letter-alert-threshold:1000}") private int deadThreshold;
    @Value("${app.intf.ticket-due-hours:24}") private int ticketDueHours;

    public DeliveryServiceImpl(MdmOutboxDao outboxDao, IntfDeliveryDao deliveryDao,
                               IntfChannelDao channelDao, IntfTicketDao ticketDao,
                               OpenApiGatewayService gateway) {
        this.outboxDao = outboxDao;
        this.deliveryDao = deliveryDao;
        this.channelDao = channelDao;
        this.ticketDao = ticketDao;
        this.gateway = gateway;
    }

    // ---------------------------------------------------------------- 一轮扫描

    @Override
    public int scanAndDeliver() {
        int handled = 0;
        handled += registerNewEvents();
        resolveConcurrentConflicts();
        handled += deliverDue();
        handled += recoverStale();
        checkQueueLimits();
        return handled;
    }

    /** 为 outbox 中尚未建投递行的接口类事件建行；幂等键缺失则自动补全并留痕（BR-4.9-12） */
    private int registerNewEvents() {
        int n = 0;
        List<MdmOutboxEvent> events = outboxDao.selectUndeliveredInterface(200);
        for (MdmOutboxEvent e : events) {
            String key = e.getIdempotencyKey();
            if (key == null || key.trim().isEmpty()) {
                key = bizOf(e) + ":v" + (e.getRecordVersion() == null ? 1 : e.getRecordVersion());
                log.info("idempotency key missing, auto-filled for event {}: {}", e.getEventId(), key);
                deliveryDao.updateOutboxStatus(e.getEventId(), "PENDING",
                        "幂等键缺失，投递服务已自动补全为 " + key + "（BR-4.9-12），不拒绝发布");
            }
            IntfDelivery d = new IntfDelivery();
            d.setEventId(e.getEventId());
            d.setEventType(e.getEventType());
            d.setIdempotencyKey(key);
            d.setBizCode(bizBase(key));
            d.setPartnerCode(partnerOf(e));
            d.setStatus(IntfDelivery.ST_PENDING);
            d.setRetryCount(0);
            // 秒截断：DATETIME 列无亚秒精度，now() 毫秒会被四舍五入进位到下一秒，
            // 导致同请求内紧随的 selectDue（带毫秒参数）看不到刚建的行（NEXT_AT <= now 恒差 <1s 失败）
            d.setNextAt(LocalDateTime.now().withNano(0));
            try {
                deliveryDao.insert(d);
                n++;
            } catch (Exception ex) {
                // 同事件已建行（并发扫描），按 C-0-06 跳过
            }
        }
        return n;
    }

    /** 同一业务对象并发事件：版本号大者胜，同版本时间戳晚者胜，落败标记冲突丢弃（BR-4.9-13） */
    private void resolveConcurrentConflicts() {
        List<IntfDelivery> due = deliveryDao.selectDue(LocalDateTime.now(), 500);
        Map<String, List<IntfDelivery>> groups = due.stream()
                .filter(d -> IntfDelivery.ST_PENDING.equals(d.getStatus())
                        || IntfDelivery.ST_RETRYING.equals(d.getStatus())
                        || IntfDelivery.ST_STAGED.equals(d.getStatus()))
                .collect(Collectors.groupingBy(d -> d.getEventType() + "|" + d.getBizCode()));
        for (Map.Entry<String, List<IntfDelivery>> en : groups.entrySet()) {
            List<IntfDelivery> rows = en.getValue();
            if (rows.size() < 2) {
                continue;
            }
            rows.sort(Comparator
                    .comparingInt((IntfDelivery d) -> versionOf(d.getIdempotencyKey())).reversed()
                    .thenComparing(d -> d.getCreateDate() == null ? LocalDateTime.MIN : d.getCreateDate(),
                            Comparator.reverseOrder()));
            for (int i = 1; i < rows.size(); i++) {
                IntfDelivery loser = rows.get(i);
                loser.setStatus(IntfDelivery.ST_CONFLICT_DROPPED);
                loser.setRemark("并发冲突落败：版本号/时间戳判定胜者=" + rows.get(0).getEventId() + "（BR-4.9-13）");
                deliveryDao.updateById(loser);
                deliveryDao.updateOutboxStatus(loser.getEventId(), "DROPPED",
                        "冲突丢弃，胜出事件 " + rows.get(0).getEventId());
                log.info("event conflict dropped: {} -> {}", loser.getEventId(), loser.getRemark());
            }
        }
    }

    /** 到期投递 */
    private int deliverDue() {
        int n = 0;
        for (IntfDelivery d : deliveryDao.selectDue(LocalDateTime.now(), 500)) {
            if (IntfDelivery.ST_CONFLICT_DROPPED.equals(d.getStatus())
                    || IntfDelivery.ST_ABANDONED.equals(d.getStatus())) {
                continue;
            }
            deliverOne(d);
            n++;
        }
        return n;
    }

    private void deliverOne(IntfDelivery d) {
        LocalDateTime now = LocalDateTime.now();
        d.setStatus(IntfDelivery.ST_DELIVERING);
        d.setLastAt(now);
        deliveryDao.updateById(d);

        long start = System.currentTimeMillis();
        DeliveryOutcome outcome = attempt(d);
        long cost = System.currentTimeMillis() - start;

        if (outcome.success) {
            d.setStatus(IntfDelivery.ST_DELIVERED);
            d.setAckAt(LocalDateTime.now());
            d.setCostMs((int) cost);
            d.setFailReason(null);
            d.setRemark(outcome.note);
            deliveryDao.updateById(d);
            deliveryDao.updateOutboxStatus(d.getEventId(), "DELIVERED",
                    "接口推送通道已投递，耗时 " + cost + "ms（" + outcome.note + "）");
            return;
        }

        int rc = (d.getRetryCount() == null ? 0 : d.getRetryCount()) + 1;
        d.setRetryCount(rc);
        d.setCostMs((int) cost);
        d.setFailReason(outcome.error);
        if (rc > retryMax) {
            markDead(d, outcome.error);
            return;
        }
        d.setStatus(outcome.connectionError ? IntfDelivery.ST_STAGED : IntfDelivery.ST_RETRYING);
        long backoff = BACKOFF_SECONDS[Math.min(rc - 1, BACKOFF_SECONDS.length - 1)];
        d.setNextAt(LocalDateTime.now().plusSeconds(backoff));
        deliveryDao.updateById(d);
        deliveryDao.updateOutboxStatus(d.getEventId(), "PENDING",
                "投递失败第 " + rc + " 次：" + outcome.error + "，" + backoff + "s 后重投");
    }

    /** 实际投递动作：有回调地址则 HTTP POST，无回调地址按台账确认（design D3） */
    private DeliveryOutcome attempt(IntfDelivery d) {
        IntfChannel channel = d.getPartnerCode() == null ? null : channelDao.selectByPartnerCode(d.getPartnerCode());
        if (channel == null || channel.getCallbackUrl() == null || channel.getCallbackUrl().trim().isEmpty()) {
            return DeliveryOutcome.ok("无回调地址，按台账确认投递");
        }
        MdmOutboxEvent e = outboxDao.selectById(d.getEventId());
        String payload = e == null || e.getPayload() == null ? "{}" : e.getPayload();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(channel.getCallbackUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(30000);   // 消费超时 >30s 视为失败（BR-4.9-14）
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Event-Type", d.getEventType());
            conn.setRequestProperty("X-Idempotency-Key", d.getIdempotencyKey());
            conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                InputStream in = conn.getInputStream();
                if (in != null) {
                    in.readAllBytes();
                    in.close();
                }
                return DeliveryOutcome.ok("回调返回 " + status);
            }
            return DeliveryOutcome.fail("回调返回 HTTP " + status, false);
        } catch (java.io.IOException ex) {
            return DeliveryOutcome.fail("回调不可达：" + ex.getMessage(), true);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 超时未 ACK：DELIVERING 停留 >30 秒按失败重试（BR-4.9-14） */
    private int recoverStale() {
        int n = 0;
        for (IntfDelivery d : deliveryDao.selectStale(LocalDateTime.now().minusSeconds(30), 100)) {
            int rc = (d.getRetryCount() == null ? 0 : d.getRetryCount()) + 1;
            d.setRetryCount(rc);
            d.setFailReason("30 秒内未收到消费确认（ACK），判定投递失败");
            if (rc > retryMax) {
                markDead(d, d.getFailReason());
            } else {
                d.setStatus(IntfDelivery.ST_RETRYING);
                d.setNextAt(LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[Math.min(rc - 1, 5)]));
                deliveryDao.updateById(d);
            }
            n++;
        }
        return n;
    }

    /** 暂存队列与死信积压阈值告警（BR-4.9-16 / SOP-5.5-C 步骤7） */
    private void checkQueueLimits() {
        try {
            int staged = deliveryDao.countStaged();
            if (staged >= stagedLimit) {
                gateway.raiseAlert("DELIVERY_STAGED_LIMIT", "EVENT_DELIVERY", IntfSlaAlert.LVL_EMERGENCY,
                        "本地暂存队列达上限 " + stagedLimit + " 条（当前 " + staged + "），告警并升级接口运维主管", null);
            }
            int dead = deliveryDao.countDead();
            if (dead >= deadThreshold) {
                gateway.raiseAlert("DELIVERY_DEAD_LIMIT", "EVENT_DELIVERY", IntfSlaAlert.LVL_EMERGENCY,
                        "死信积压 " + dead + " 条超过阈值 " + deadThreshold + "，触发紧急处理流程", null);
            }
        } catch (Exception e) {
            log.warn("queue limit check failed: {}", e.getMessage());
        }
    }

    private void markDead(IntfDelivery d, String reason) {
        d.setStatus(IntfDelivery.ST_DEAD);
        d.setNextAt(null);
        deliveryDao.updateById(d);
        deliveryDao.updateOutboxStatus(d.getEventId(), "DEAD",
                "重试 " + d.getRetryCount() + " 次仍失败，转入死信（原幂等键 " + d.getIdempotencyKey() + " 保留）");
        createDeadTicket(d, reason);
        log.warn("event {} dead-lettered after {} retries: {}", d.getEventId(), d.getRetryCount(), reason);
    }

    /** 死信自动生成人工处理工单，保留原幂等键（C-4.9-02 / BR-4.9-15） */
    private void createDeadTicket(IntfDelivery d, String reason) {
        if (ticketDao.selectByRef(d.getId(), IntfTicket.SRC_EVENT_DEAD) != null) {
            return;
        }
        IntfTicket t = new IntfTicket();
        t.setTicketNo(nextTicketNo());
        t.setSource(IntfTicket.SRC_EVENT_DEAD);
        t.setRefId(d.getId());
        t.setPartnerCode(d.getPartnerCode());
        t.setIdempotencyKey(d.getIdempotencyKey());
        t.setTitle("事件投递死信：" + d.getEventType());
        t.setErrorLoc("事件 " + d.getEventId() + " 重投 " + d.getRetryCount() + " 次仍失败");
        t.setDetail("失败原因：" + reason + "\n原幂等键（重放必须保留）：" + d.getIdempotencyKey());
        t.setPriority("P1");
        t.setStatus(IntfTicket.ST_OPEN);
        t.setAssignTo("intf-ops01");
        t.setDueAt(LocalDateTime.now().plusHours(ticketDueHours));
        ticketDao.insert(t);
        d.setTicketId(t.getId());
        deliveryDao.updateById(d);
    }

    // ---------------------------------------------------------------- 人工动作

    @Override
    public Map<String, Object> replay(String deliveryId) {
        IntfDelivery d = require(deliveryId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (IntfDelivery.ST_DELIVERED.equals(d.getStatus())) {
            result.put("skipped", true);
            result.put("message", "已投递，跳过（复用原幂等键，不产生重复业务效果）");
            return result;
        }
        if (IntfDelivery.ST_CONFLICT_DROPPED.equals(d.getStatus())) {
            throw new ServiceException(422, "并发冲突落败事件不可重放，请确认胜出事件的投递结果");
        }
        d.setStatus(IntfDelivery.ST_RETRYING);
        d.setNextAt(LocalDateTime.now());
        d.setReplayCount((d.getReplayCount() == null ? 0 : d.getReplayCount()) + 1);
        d.setReplayBy(IntfGuard.currentUser());
        d.setRemark("人工重放，保留原幂等键 " + d.getIdempotencyKey());
        deliveryDao.updateById(d);
        result.put("skipped", false);
        result.put("idempotencyKey", d.getIdempotencyKey());
        result.put("replayCount", d.getReplayCount());
        return result;
    }

    @Override
    public Map<String, Object> abandon(String deliveryId, String note) {
        IntfGuard.requireAdmin("死信放弃");
        IntfDelivery d = require(deliveryId);
        if (!IntfDelivery.ST_DEAD.equals(d.getStatus())) {
            throw new ServiceException(422, "仅死信状态可标记放弃");
        }
        d.setStatus(IntfDelivery.ST_ABANDONED);
        d.setRemark("人工放弃：" + note);
        deliveryDao.updateById(d);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", d.getStatus());
        result.put("note", note);
        return result;
    }

    @Override
    public Map<String, Object> handleTicket(String ticketId, String note, boolean replay) {
        IntfTicket t = ticketDao.selectById(ticketId);
        if (t == null) {
            throw new ServiceException(404, "工单不存在");
        }
        if (replay && t.getRefId() != null) {
            replay(t.getRefId());
        }
        t.setStatus(replay ? IntfTicket.ST_PROCESSING : IntfTicket.ST_DONE);
        if (!replay) {
            t.setHandledBy(IntfGuard.currentUser());
            t.setHandledAt(LocalDateTime.now());
            t.setHandlerNote(note);
        }
        ticketDao.updateById(t);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketNo", t.getTicketNo());
        result.put("status", t.getStatus());
        result.put("replayed", replay);
        return result;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public Page<IntfDelivery> page(long current, long size, String eventType, String status) {
        LambdaQueryWrapper<IntfDelivery> qw = new LambdaQueryWrapper<>();
        if (eventType != null && !eventType.trim().isEmpty()) {
            qw.like(IntfDelivery::getEventType, eventType.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(IntfDelivery::getStatus, status.trim());
        }
        qw.orderByDesc(IntfDelivery::getCreateDate);
        return deliveryDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivered", count(IntfDelivery.ST_DELIVERED));
        m.put("retrying", count(IntfDelivery.ST_RETRYING) + count(IntfDelivery.ST_STAGED)
                + count(IntfDelivery.ST_PENDING) + count(IntfDelivery.ST_DELIVERING));
        m.put("dead", deliveryDao.countDead());
        m.put("staged", deliveryDao.countStaged());
        m.put("openTickets", ticketDao.countOpen());
        return m;
    }

    private long count(String status) {
        return deliveryDao.selectCount(new LambdaQueryWrapper<IntfDelivery>()
                .eq(IntfDelivery::getStatus, status));
    }

    private IntfDelivery require(String id) {
        IntfDelivery d = deliveryDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "投递记录不存在");
        }
        return d;
    }

    // ---------------------------------------------------------------- helpers

    private String nextTicketNo() {
        String prefix = "IT" + LocalDateTime.now().format(NO_FMT);
        Integer max = ticketDao.selectMaxSeq(prefix);
        int seq = (max == null ? 0 : max) + 1;
        return prefix + String.format("%06d", seq);
    }

    /** 从事件载荷中解析目标伙伴（无则 null，按台账确认投递） */
    private String partnerOf(MdmOutboxEvent e) {
        String payload = e.getPayload();
        if (payload == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"partnerCode\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    private String bizOf(MdmOutboxEvent e) {
        if (e.getIdempotencyKey() != null && !e.getIdempotencyKey().trim().isEmpty()) {
            return bizBase(e.getIdempotencyKey());
        }
        return e.getEventId();
    }

    /** 幂等键形如 bizCode:vN，业务对象 = 去掉版本后缀 */
    private static String bizBase(String key) {
        if (key == null) {
            return "";
        }
        int idx = key.lastIndexOf(":v");
        return idx > 0 ? key.substring(0, idx) : key;
    }

    private static int versionOf(String key) {
        if (key == null) {
            return 0;
        }
        int idx = key.lastIndexOf(":v");
        if (idx < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(idx + 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class DeliveryOutcome {
        final boolean success;
        final boolean connectionError;
        final String error;
        final String note;

        private DeliveryOutcome(boolean success, boolean connectionError, String error, String note) {
            this.success = success;
            this.connectionError = connectionError;
            this.error = error;
            this.note = note;
        }

        static DeliveryOutcome ok(String note) {
            return new DeliveryOutcome(true, false, null, note);
        }

        static DeliveryOutcome fail(String error, boolean connectionError) {
            return new DeliveryOutcome(false, connectionError, error, null);
        }
    }
}

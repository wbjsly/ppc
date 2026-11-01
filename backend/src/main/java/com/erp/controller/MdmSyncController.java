package com.erp.controller;

import com.erp.common.R;
import com.erp.mdm.cache.MdmReadCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 1.8 集成同步台账：1.8.1 变更事件（outbox MDM 视图）+ 1.8.2 下游刷新
 * （内部监听执行台账 + 外部投递状态 + 缓存强刷）。
 */
@Tag(name = "MDM 集成同步台账")
@RestController
@RequestMapping("/api/mdm/events")
public class MdmSyncController {

    private final JdbcTemplate jdbc;
    private final MdmReadCache readCache;

    public MdmSyncController(JdbcTemplate jdbc, MdmReadCache readCache) {
        this.jdbc = jdbc;
        this.readCache = readCache;
    }

    @Operation(summary = "1.8.1 变更事件台账（outbox MDM 事件筛选）")
    @GetMapping
    public R<Map<String, Object>> events(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        StringBuilder where = new StringBuilder("WHERE EVENT_TYPE LIKE 'MDM.%'");
        if (type != null && !type.isBlank()) {
            where.append(" AND EVENT_TYPE = ?");
        }
        if (entityId != null && !entityId.isBlank()) {
            where.append(" AND PAYLOAD LIKE ?");
        }
        List<Object> args = new java.util.ArrayList<>();
        if (type != null && !type.isBlank()) {
            args.add(type.trim());
        }
        if (entityId != null && !entityId.isBlank()) {
            args.add("%" + entityId.trim() + "%");
        }
        int lim = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * lim;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT EVENT_ID AS eventId, EVENT_TYPE AS eventType, RECORD_VERSION AS recordVersion, "
                        + "STATUS AS status, OCCURRED_AT AS occurredAt, IDEMPOTENCY_KEY AS idempotencyKey, "
                        + "PAYLOAD AS payload FROM erp_ops_outbox " + where
                        + " ORDER BY OCCURRED_AT DESC LIMIT ? OFFSET ?",
                concat(args, lim, offset));
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_ops_outbox " + where,
                Integer.class, args.toArray());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total == null ? rows.size() : total);
        return R.ok(out);
    }

    @Operation(summary = "1.8.2 内部监听执行台账")
    @GetMapping("/listeners")
    public R<List<Map<String, Object>>> listeners(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        StringBuilder sql = new StringBuilder("SELECT ID AS id, EVENT_ID AS eventId, "
                + "EVENT_TYPE AS eventType, ENTITY_ID AS entityId, LISTENER_NAME AS listenerName, "
                + "STATUS AS status, RETRY_CNT AS retryCnt, EXEC_MS AS execMs, ERROR_MSG AS errorMsg, "
                + "WORK_TICKET_ID AS workTicketId, CREATE_DATE AS createDate "
                + "FROM erp_mdm_listener_log WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY CREATE_DATE DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @Operation(summary = "1.8.2 外部投递状态（MDM 事件，复用投递查询）")
    @GetMapping("/deliveries")
    public R<Map<String, Object>> deliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 沿用 intf 投递表口径：MDM 事件前缀筛选
        StringBuilder where = new StringBuilder("WHERE EVENT_TYPE LIKE 'MDM.%'");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND STATUS = ?");
            args.add(status.trim());
        }
        int lim = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ID AS id, EVENT_ID AS eventId, EVENT_TYPE AS eventType, "
                        + "PARTNER_CODE AS partnerCode, STATUS AS status, RETRY_COUNT AS retryCnt, "
                        + "IDEMPOTENCY_KEY AS idempotencyKey, FAIL_REASON AS errorMsg "
                        + "FROM erp_intf_delivery " + where
                        + " ORDER BY CREATE_DATE DESC LIMIT ? OFFSET ?",
                concat(args, lim, Math.max(page - 1, 0) * lim));
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_intf_delivery " + where,
                Integer.class, args.toArray());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total == null ? rows.size() : total);
        return R.ok(out);
    }

    @Operation(summary = "缓存强刷（ADMIN）")
    @PostMapping("/cache-invalidate")
    public R<Map<String, Object>> cacheInvalidate() {
        readCache.invalidateAllTypes();
        return R.ok(Map.of("ok", true));
    }

    private Object[] concat(List<Object> args, int lim, int offset) {
        List<Object> all = new java.util.ArrayList<>(args);
        all.add(lim);
        all.add(offset);
        return all.toArray();
    }
}

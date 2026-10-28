package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfCredentialDao;
import com.erp.dao.intf.IntfRateStateDao;
import com.erp.entity.intf.IntfCallLog;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfCredential;
import com.erp.entity.intf.IntfRateState;
import com.erp.service.intf.TierChangeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 12.3 接口调用操作台（add-interface-call-console，spec interface-call-console）：
 * 认证拒绝原因分析 / 限流状态与降档历史 / 429 趋势 / Trace 下钻 / 审计聚合。
 * 全只读——调档与凭证写动作仍在 12.1.3 与 2.8.3（design D5/B2）。
 * 权限落 SecurityConfig `/api/intf/**` 兜底（ADMIN / INTF_OPS）。
 */
@Tag(name = "接口调用操作台")
@RestController
@RequestMapping("/api/intf/call-console")
public class IntfCallConsoleController {

    private final IntfCallLogDao callLogDao;
    private final IntfChannelDao channelDao;
    private final IntfCredentialDao credentialDao;
    private final IntfRateStateDao rateStateDao;
    private final TierChangeLogService tierChangeLog;

    public IntfCallConsoleController(IntfCallLogDao callLogDao, IntfChannelDao channelDao,
                                     IntfCredentialDao credentialDao, IntfRateStateDao rateStateDao,
                                     TierChangeLogService tierChangeLog) {
        this.callLogDao = callLogDao;
        this.channelDao = channelDao;
        this.credentialDao = credentialDao;
        this.rateStateDao = rateStateDao;
        this.tierChangeLog = tierChangeLog;
    }

    // ---------------- 12.3.1 认证鉴权 ----------------

    @Operation(summary = "认证拒绝原因聚合 + 明细下钻（400/401/403，spec 12.3.1）")
    @GetMapping("/auth-rejections")
    public R<Map<String, Object>> authRejections(
            @RequestParam(required = false) String caller,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        List<Map<String, Object>> groups = callLogDao.selectAuthRejectionGroups(trim(caller), from, to);

        LambdaQueryWrapper<IntfCallLog> qw = new LambdaQueryWrapper<>();
        // 状态码三选一：独立嵌套组，避免 OR 链与后续 AND 的优先级问题
        qw.and(w -> w.eq(IntfCallLog::getRespCode, 400)
                .or().eq(IntfCallLog::getRespCode, 401)
                .or().eq(IntfCallLog::getRespCode, 403));
        if (trim(caller) != null || from != null || to != null) {
            qw.and(w -> {
                if (trim(caller) != null) {
                    w.eq(IntfCallLog::getCaller, trim(caller));
                }
                if (from != null) {
                    w.ge(IntfCallLog::getCallAt, from);
                }
                if (to != null) {
                    w.le(IntfCallLog::getCallAt, to);
                }
            });
        }
        applyReasonFilter(qw, reason);
        qw.orderByDesc(IntfCallLog::getCallAt);
        Page<IntfCallLog> detail = callLogDao.selectPage(new Page<>(current, size), qw);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groups);
        out.put("detail", detail);
        return R.ok(out);
    }

    /** design D3：四类可观测原因 → SQL 过滤条件（各自嵌套 AND 组） */
    private void applyReasonFilter(LambdaQueryWrapper<IntfCallLog> qw, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return;
        }
        switch (reason.trim()) {
            case "TIMESTAMP_SKEW" -> qw.and(w -> w.eq(IntfCallLog::getRespCode, 400));
            case "SECURITY_EVENT" -> qw.and(w -> w.likeRight(IntfCallLog::getErrCode, "SECURITY_EVENT"));
            case "SCOPE_DENIED" -> qw.and(w -> w.eq(IntfCallLog::getRespCode, 403));
            case "CREDENTIAL_AUTH_FAILED" -> qw.and(w -> w.eq(IntfCallLog::getRespCode, 401)
                    .and(x -> x.isNull(IntfCallLog::getErrCode)
                            .or().notLikeRight(IntfCallLog::getErrCode, "SECURITY_EVENT")));
            default -> { /* 未知原因不追加过滤，退回全量 */ }
        }
    }

    @Operation(summary = "凭证只读清单（写操作跳 12.1.3 / 2.8.3，spec 12.3.1）")
    @GetMapping("/credentials")
    public R<Page<IntfCredential>> credentials(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<IntfCredential> qw = new LambdaQueryWrapper<>();
        qw.orderByDesc(IntfCredential::getCreateDate);
        return R.ok(credentialDao.selectPage(new Page<>(current, size), qw));
    }

    // ---------------- 12.3.2 限流控制 ----------------

    @Operation(summary = "限流状态总览：通道档位/熔断 + 最新统计窗口（spec 12.3.2）")
    @GetMapping("/rate-overview")
    public R<Map<String, Object>> rateOverview() {
        List<IntfChannel> channels = channelDao.selectList(new LambdaQueryWrapper<IntfChannel>()
                .eq(IntfChannel::getDelFlag, "0").orderByAsc(IntfChannel::getPartnerCode));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IntfChannel ch : channels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ch.getId());
            m.put("partnerCode", ch.getPartnerCode());
            m.put("rateTier", ch.getRateTier());
            m.put("limitPerMin", ch.getLimitPerMin());
            m.put("bucketCapacity", ch.getLimitPerMin() == null ? null : (int) (ch.getLimitPerMin() * 1.5));
            m.put("circuitState", ch.getCircuitState());
            m.put("downgradeFrom", ch.getDowngradeFrom());
            m.put("downgradeAt", ch.getDowngradeAt());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", rows);
        out.put("windows", rateStateDao.selectLatestWindows());
        return R.ok(out);
    }

    @Operation(summary = "档位变更历史（自动降档+手动调档同源留痕，spec 12.3.2）")
    @GetMapping("/tier-changes")
    public R<Map<String, Object>> tierChanges(@RequestParam(required = false) String caller,
                                              @RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(tierChangeLog.history(trim(caller), current, size));
    }

    @Operation(summary = "429 触发趋势（默认 24h、上限 31 天，总数与明细可对账，spec 12.3.2）")
    @GetMapping("/rate-trend")
    public R<Map<String, Object>> rateTrend(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String caller) {
        int h = Math.min(Math.max(hours, 1), 31 * 24);
        LocalDateTime from = LocalDateTime.now().minusHours(h);
        int hourBuckets = h <= 48 ? 1 : 0;
        List<Map<String, Object>> buckets = callLogDao.selectRateTrend(from, trim(caller), hourBuckets);
        long total = callLogDao.countRateTrend(from, trim(caller));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("hours", h);
        out.put("buckets", buckets);
        out.put("total", total);
        return R.ok(out);
    }

    // ---------------- 12.3.3 调用审计 ----------------

    @Operation(summary = "Trace 下钻：按 X-Request-Id 单次调用全记录（含被拒请求，spec 12.3.3）")
    @GetMapping("/trace/{reqId}")
    public R<List<IntfCallLog>> trace(@PathVariable String reqId) {
        return R.ok(callLogDao.selectTrace(reqId));
    }

    @Operation(summary = "审计聚合：调用方×接口×日（调用量/成功率/P95/4xx/5xx，spec 12.3.3）")
    @GetMapping("/audit-aggregates")
    public R<List<Map<String, Object>>> auditAggregates(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String caller) {
        LocalDateTime from = (dateFrom == null ? LocalDate.now().minusDays(7) : dateFrom).atStartOfDay();
        LocalDateTime to = (dateTo == null ? LocalDate.now() : dateTo).atTime(23, 59, 59);
        return R.ok(callLogDao.selectAuditAggregates(from, to, trim(caller)));
    }

    private static String trim(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}

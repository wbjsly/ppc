package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfCredentialDao;
import com.erp.dao.intf.IntfDeliveryDao;
import com.erp.dao.intf.IntfSlaAlertDao;
import com.erp.dao.intf.IntfTicketDao;
import com.erp.entity.intf.IntfCallLog;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfCredential;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfTicket;
import com.erp.security.IntfGuard;
import com.erp.service.intf.OpenApiGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行总览与通道防护（spec interface-console 8.2 + open-api-gateway 2.5/2.6 查询侧）。
 * 高危动作（解除熔断、限流降档/恢复）服务端二次校验 ADMIN。
 */
@Tag(name = "接口运行总览")
@RestController
@RequestMapping("/api/intf")
public class IntfConsoleController {

    private final IntfCallLogDao callLogDao;
    private final IntfChannelDao channelDao;
    private final IntfCredentialDao credentialDao;
    private final IntfDeliveryDao deliveryDao;
    private final IntfSlaAlertDao alertDao;
    private final IntfTicketDao ticketDao;
    private final OpenApiGatewayService gateway;

    public IntfConsoleController(IntfCallLogDao callLogDao, IntfChannelDao channelDao,
                                 IntfCredentialDao credentialDao, IntfDeliveryDao deliveryDao,
                                 IntfSlaAlertDao alertDao, IntfTicketDao ticketDao,
                                 OpenApiGatewayService gateway) {
        this.callLogDao = callLogDao;
        this.channelDao = channelDao;
        this.credentialDao = credentialDao;
        this.deliveryDao = deliveryDao;
        this.alertDao = alertDao;
        this.ticketDao = ticketDao;
        this.gateway = gateway;
    }

    @Operation(summary = "运行总览 KPI（台账实时聚合，无数据显示 0 并标「暂无数据」）")
    @GetMapping("/console/kpi")
    public R<Map<String, Object>> kpi() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        List<IntfCallLog> rows = callLogDao.selectList(new LambdaQueryWrapper<IntfCallLog>()
                .ge(IntfCallLog::getCallAt, today).last("LIMIT 20000"));
        int total = rows.size();
        int ok = 0;
        int rateHits = 0;
        int p95 = 0;
        java.util.List<Integer> costs = new java.util.ArrayList<>();
        for (IntfCallLog r : rows) {
            if (r.getRespCode() != null && r.getRespCode() < 400) {
                ok++;
            }
            if (Boolean.TRUE.equals(r.getRateHit())) {
                rateHits++;
            }
            if (r.getCostMs() != null) {
                costs.add(r.getCostMs());
            }
        }
        costs.sort(Integer::compareTo);
        if (!costs.isEmpty()) {
            p95 = costs.get((int) Math.ceil(costs.size() * 0.95) - 1);
        }
        IntfChannel ch = channelDao.selectByPartnerCode("INTF-DEMO");
        if (ch == null) {
            List<IntfChannel> all = channelDao.selectList(new LambdaQueryWrapper<IntfChannel>()
                    .last("LIMIT 1"));
            ch = all.isEmpty() ? null : all.get(0);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasData", total > 0);
        m.put("emptyHint", total > 0 ? "" : "暂无数据");
        m.put("totalCalls", total);
        m.put("successCalls", ok);
        m.put("successRate", total == 0 ? 0
                : java.math.BigDecimal.valueOf(ok * 100L).divide(java.math.BigDecimal.valueOf(total), 2,
                java.math.RoundingMode.HALF_UP));
        m.put("p95", p95);
        m.put("rateHits", rateHits);
        m.put("circuitState", ch == null ? "CLOSED" : ch.getCircuitState());
        m.put("rateTier", ch == null ? "-" : ch.getRateTier());
        m.put("openAlerts", alertDao.countOpen());
        m.put("deadBacklog", deliveryDao.countDead());
        m.put("openTickets", ticketDao.countOpen());
        m.put("activeCredentials", credentialDao.selectCount(new LambdaQueryWrapper<IntfCredential>()
                .eq(IntfCredential::getStatus, IntfCredential.ST_ACTIVE)));
        m.put("auditFailClosed", "已启用（C-5.5-03：写失败即 503）");
        return R.ok(m);
    }

    @Operation(summary = "通道列表（限流档位 / 熔断状态 / 证书台账）")
    @GetMapping("/channels")
    public R<List<IntfChannel>> channels() {
        return R.ok(channelDao.selectList(new LambdaQueryWrapper<IntfChannel>()
                .orderByDesc(IntfChannel::getCreateDate)));
    }

    @Operation(summary = "解除熔断（高危：仅 ADMIN，spec S-5.5-06 运维判定后恢复）")
    @PostMapping("/channels/{id}/circuit-reset")
    public R<Map<String, Object>> circuitReset(@PathVariable String id) {
        IntfGuard.requireAdmin("解除熔断");
        IntfChannel ch = channelDao.selectById(id);
        if (ch == null) {
            throw new ServiceException(404, "通道不存在");
        }
        gateway.resetCircuit(ch, "运维手动解除封禁并恢复限流档位");
        return R.ok(Map.<String, Object>of("id", ch.getId(), "circuitState", IntfChannel.CIRCUIT_CLOSED,
                "rateTier", ch.getRateTier()));
    }

    @Operation(summary = "限流档位调整（高危：仅 ADMIN，降档/恢复留痕）")
    @PostMapping("/channels/{id}/rate-tier")
    public R<Map<String, Object>> rateTier(@PathVariable String id, @RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("限流档位调整");
        IntfChannel ch = channelDao.selectById(id);
        if (ch == null) {
            throw new ServiceException(404, "通道不存在");
        }
        String tier = String.valueOf(body.get("tier")).trim().toUpperCase();
        if (!IntfChannel.TIER_STRATEGIC.equals(tier) && !IntfChannel.TIER_NORMAL.equals(tier)
                && !IntfChannel.TIER_NEW.equals(tier) && !IntfChannel.TIER_LOWEST.equals(tier)) {
            throw new ServiceException(400, "tier 仅支持 STRATEGIC / NORMAL / NEW / LOWEST");
        }
        String before = ch.getRateTier();
        ch.setRateTier(tier);
        ch.setLimitPerMin(gateway.limitForTier(tier));
        if (IntfChannel.TIER_LOWEST.equals(tier)) {
            ch.setDowngradeAt(LocalDateTime.now());
            ch.setDowngradeFrom(before);
        }
        channelDao.updateById(ch);
        return R.ok(Map.<String, Object>of("id", ch.getId(), "rateTier", tier,
                "limitPerMin", ch.getLimitPerMin(), "before", before));
    }

    @Operation(summary = "调用审计日志（按调用方/接口/时间，敏感字段已脱敏）")
    @GetMapping("/call-logs")
    public R<Page<IntfCallLog>> callLogs(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String caller,
                                         @RequestParam(required = false) String path) {
        LambdaQueryWrapper<IntfCallLog> qw = new LambdaQueryWrapper<>();
        qw.eq(caller != null && !caller.trim().isEmpty(), IntfCallLog::getCaller, caller == null ? "" : caller.trim());
        qw.like(path != null && !path.trim().isEmpty(), IntfCallLog::getApiPath, path == null ? "" : path.trim());
        qw.orderByDesc(IntfCallLog::getCallAt);
        return R.ok(callLogDao.selectPage(new Page<>(current, size), qw));
    }

    @Operation(summary = "人工工单分页（死信/报文待人工/版本不兼容）")
    @GetMapping("/tickets")
    public R<List<IntfTicket>> tickets(@RequestParam(required = false) String status) {
        return R.ok(ticketDao.selectList(new LambdaQueryWrapper<IntfTicket>()
                .eq(status != null && !status.trim().isEmpty(), IntfTicket::getStatus, status == null ? "" : status.trim())
                .orderByDesc(IntfTicket::getCreateDate)));
    }
}

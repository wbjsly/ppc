package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.vmi.VmiAlertDao;
import com.erp.dao.vmi.VmiSettlementDao;
import com.erp.entity.vmi.VmiAlert;
import com.erp.entity.vmi.VmiSettlement;
import com.erp.security.PortalContext;
import com.erp.service.vmi.VmiPortalSyncService;
import com.erp.service.vmi.VmiSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VMI 门户同步（spec vmi-portal-sync + vmi-consignment MODIFIED 门户确认）。
 * 内部：水位同步触发（ADMIN/PM，走 /api/proc/vmi/** 既有规则）；
 * 门户：水位快照 / 补货确认 / 待确认结算 / 结算门户确认（行级隔离）。
 */
@Tag(name = "VMI 门户同步")
@RestController
public class VmiPortalSyncController {

    private final VmiPortalSyncService syncService;
    private final VmiSettlementService settlementService;
    private final VmiAlertDao alertDao;
    private final VmiSettlementDao settleDao;
    private final PortalContext portalContext;

    public VmiPortalSyncController(VmiPortalSyncService syncService,
                                   VmiSettlementService settlementService,
                                   VmiAlertDao alertDao,
                                   VmiSettlementDao settleDao,
                                   PortalContext portalContext) {
        this.syncService = syncService;
        this.settlementService = settlementService;
        this.alertDao = alertDao;
        this.settleDao = settleDao;
        this.portalContext = portalContext;
    }

    // ---------- 内部 ----------

    @Operation(summary = "水位同步触发（惰性，生成 WATER_SYNCED 推送批次）")
    @PostMapping("/api/proc/vmi/water-sync")
    public R<Map<String, Object>> triggerWaterSync(
            @RequestBody(required = false) Map<String, Object> payload) {
        Object sid = payload == null ? null : payload.get("supplierId");
        return R.ok(syncService.scanWater(sid == null ? null : String.valueOf(sid)));
    }

    @Operation(summary = "补货建议代录确认（ADMIN/PM，来源 OFFLINE）")
    @PostMapping("/api/proc/vmi/replenish-confirms/{alertId}/confirm")
    public R<Map<String, Object>> offlineConfirmReplenish(@PathVariable String alertId,
                                                          @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        return R.ok(syncService.confirmReplenish(alertId, body, "OFFLINE"));
    }

    // ---------- 门户（全部行级隔离） ----------

    @Operation(summary = "我的水位同步（加载即同步，spec vmi-portal-sync）")
    @GetMapping("/api/portal/vmi/water")
    public R<Map<String, Object>> portalWater() {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(syncService.scanWater(supplierId));
    }

    @Operation(summary = "我的告警/补货建议")
    @GetMapping("/api/portal/vmi/alerts")
    public R<Page<Map<String, Object>>> portalAlerts(
            @RequestParam(defaultValue = "REPLENISH") String alertType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(syncService.alerts(current, size, alertType, status, supplierId));
    }

    @Operation(summary = "补货建议确认（联动创建 ASN）")
    @PostMapping("/api/portal/vmi/alerts/{alertId}/confirm")
    public R<Map<String, Object>> confirmReplenish(@PathVariable String alertId,
                                                   @RequestBody(required = false) Map<String, Object> payload) {
        String supplierId = portalContext.requireSupplierId();
        VmiAlert alert = alertDao.selectById(alertId);
        if (alert == null || !supplierId.equals(alert.getSupplierId())) {
            throw new ServiceException(404, "补货建议不存在");   // 行级隔离（C-4.9-06）
        }
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        body.put("operatorName", portalContext.currentUser().getUsername());
        return R.ok(syncService.confirmReplenish(alertId, body, "PORTAL"));
    }

    @Operation(summary = "我的待确认结算单")
    @GetMapping("/api/portal/vmi/settlements")
    public R<Page<Map<String, Object>>> portalSettlements(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(settlementService.page(current, size, supplierId, status));
    }

    @Operation(summary = "结算单门户确认（side=supplier，来源 PORTAL）")
    @PostMapping("/api/portal/vmi/settlements/{id}/sign")
    public R<Map<String, Object>> portalSign(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> payload) {
        String supplierId = portalContext.requireSupplierId();
        VmiSettlement s = settleDao.selectById(id);
        if (s == null || !supplierId.equals(s.getSupplierId())) {
            throw new ServiceException(404, "结算单不存在");     // 行级隔离
        }
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        body.putIfAbsent("side", "supplier");
        body.put("source", "PORTAL");
        body.put("supplierConfirmBy", portalContext.currentUser().getUsername());
        return R.ok(settlementService.sign(id, body));
    }
}

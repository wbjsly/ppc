package com.erp.controller;

import com.erp.common.R;
import com.erp.security.PortalContext;
import com.erp.service.portal.PortalEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;

/**
 * 推送台账（spec portal-event-push）：内部 /api/proc/portal-events（三角色），
 * 门户 /api/portal/events（行级隔离，仅绑定供应商事件）。
 */
@Tag(name = "门户推送台账")
@RestController
public class PortalEventController {

    private final PortalEventService service;
    private final PortalContext portalContext;

    public PortalEventController(PortalEventService service, PortalContext portalContext) {
        this.service = service;
        this.portalContext = portalContext;
    }

    @Operation(summary = "推送台账（内部）")
    @GetMapping("/api/proc/portal-events")
    public R<Page<Map<String, Object>>> ledger(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.ledger(eventType, supplierId, keyword, from, to, current, size));
    }

    @Operation(summary = "我的推送记录（门户，行级隔离）")
    @GetMapping("/api/portal/events")
    public R<Page<Map<String, Object>>> portalLedger(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(service.ledger(eventType, supplierId, keyword, null, null, current, size));
    }
}

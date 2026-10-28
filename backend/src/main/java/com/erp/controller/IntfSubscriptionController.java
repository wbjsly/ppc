package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.intf.IntfSubscription;
import com.erp.service.intf.EventSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 事件订阅配置（add-event-subscription，菜单 12.2.2）。
 * 伙伴 × 事件类型 UK 409；URL 空回落通道（零断流）；RETRY_MAX 1~5 行级死信阈值。
 * INTF_OPS/ADMIN 维护（SecurityConfig /api/intf/** 兜底）。
 */
@Tag(name = "接口事件订阅")
@RestController
@RequestMapping("/api/intf/subscriptions")
public class IntfSubscriptionController {

    private final EventSubscriptionService service;

    public IntfSubscriptionController(EventSubscriptionService service) {
        this.service = service;
    }

    @Operation(summary = "订阅分页（伙伴/类型/状态）")
    @GetMapping
    public R<Page<IntfSubscription>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String partnerCode,
                                          @RequestParam(required = false) String eventType,
                                          @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, partnerCode, eventType, status));
    }

    @Operation(summary = "创建订阅（UK 409；URL 空=回落通道；RETRY_MAX 1~5）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @Operation(summary = "修改订阅（改动对新投递生效——快照语义）")
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.update(id, body));
    }

    @Operation(summary = "启停订阅（停用 → 该类型新投递回落通道 URL）")
    @PostMapping("/{id}/toggle")
    public R<Map<String, Object>> toggle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        return R.ok(service.toggle(id, status == null ? "" : String.valueOf(status)));
    }
}

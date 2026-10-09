package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.proc.PoCoopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * PO 协同工作台（spec po-collaboration 页面与权限）：
 * 列表/时间线 = ADMIN/PM/WAREHOUSE；催办/代录/接受改期 = ADMIN/PM；解锁 = ADMIN。
 */
@Tag(name = "PO 协同")
@RestController
@RequestMapping("/api/proc/po-coops")
public class PoCoopController {

    private final PoCoopService service;

    public PoCoopController(PoCoopService service) {
        this.service = service;
    }

    @Operation(summary = "PO 协同列表（含惰性超时补写）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, supplierId, keyword));
    }

    @Operation(summary = "协同时间线（推送/催办/升级/确认/锁定）")
    @GetMapping("/{poId}/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable String poId) {
        return R.ok(service.timeline(poId));
    }

    @Operation(summary = "催办补发（ADMIN/PM）")
    @PostMapping("/{poId}/remind")
    public R<Map<String, Object>> remind(@PathVariable String poId) {
        return R.ok(service.remind(poId));
    }

    @Operation(summary = "代录交期确认（ADMIN/PM，来源 OFFLINE）")
    @PostMapping("/{poId}/confirm")
    public R<Map<String, Object>> offlineConfirm(@PathVariable String poId,
                                                 @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.confirm(poId, payload == null ? Map.of() : payload, "OFFLINE"));
    }

    @Operation(summary = "采购员接受改期（ADMIN/PM）")
    @PostMapping("/{poId}/change-accept")
    public R<Map<String, Object>> acceptChange(@PathVariable String poId,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(service.acceptChange(poId, payload));
    }

    @Operation(summary = "解锁供应商确认入口（ADMIN）")
    @PostMapping("/unlocks")
    public R<Map<String, Object>> unlock(@RequestBody Map<String, Object> payload) {
        Object sid = payload.get("supplierId");
        Object remark = payload.get("remark");
        return R.ok(service.unlock(sid == null ? null : String.valueOf(sid),
                remark == null ? null : String.valueOf(remark)));
    }
}

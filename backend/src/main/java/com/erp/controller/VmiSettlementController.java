package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.vmi.VmiSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * VMI 周期结算（2.8.1，spec vmi-consignment，FR-4.2-8-4 / BR-4.2-38）。
 * 读写均放行 ADMIN/PM（SecurityConfig /api/proc/vmi/** 细规则）。
 */
@Tag(name = "VMI 周期结算")
@RestController
@RequestMapping("/api/proc/vmi/settlements")
public class VmiSettlementController {

    private final VmiSettlementService service;

    public VmiSettlementController(VmiSettlementService service) {
        this.service = service;
    }

    @Operation(summary = "结算单分页（供应商/状态筛选）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, supplierId, status));
    }

    @Operation(summary = "结算单详情（头 + 领用明细对账视图）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "生成结算单（供应商 + 期间聚合领用明细）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "确认签署（side=pm 采购员 / side=supplier 供应商线下登记；超容差两签）")
    @PostMapping("/{id}/confirm")
    public R<Map<String, Object>> sign(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        return R.ok(service.sign(id, body));
    }
}

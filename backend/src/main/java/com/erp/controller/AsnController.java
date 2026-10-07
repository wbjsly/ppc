package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.proc.AsnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ASN 管理（内部，spec asn-collaboration 页面与权限）：
 * 列表/详情 = ADMIN/PM/WAREHOUSE；代录创建 = ADMIN/PM（门户创建走 /api/portal/asns）。
 */
@Tag(name = "发货通知 ASN")
@RestController
@RequestMapping("/api/proc/asns")
public class AsnController {

    private final AsnService service;

    public AsnController(AsnService service) {
        this.service = service;
    }

    @Operation(summary = "ASN 列表（含超收与到货进度）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, supplierId, keyword));
    }

    @Operation(summary = "ASN 明细（含行与核销状态）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "代录创建 ASN（ADMIN/PM）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }
}

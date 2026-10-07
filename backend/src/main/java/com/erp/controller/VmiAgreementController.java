package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.vmi.VmiAgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * VMI 寄售协议（2.8.1，spec vmi-consignment）。
 * 读放行 ADMIN/PM/WAREHOUSE；协议维护写 ADMIN/PM（SecurityConfig 细规则 + 服务层二次校验）。
 */
@Tag(name = "VMI 寄售协议")
@RestController
@RequestMapping("/api/proc/vmi/agreements")
public class VmiAgreementController {

    private final VmiAgreementService service;

    public VmiAgreementController(VmiAgreementService service) {
        this.service = service;
    }

    @Operation(summary = "协议分页（供应商/状态筛选）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, supplierId, status));
    }

    @Operation(summary = "协议详情（头 + 物料清单/水位/价格条款）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建协议（仅 ADMIN/PM）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "更新协议（仅草稿，仅 ADMIN/PM）")
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.update(id, payload));
    }

    @Operation(summary = "生效（DRAFT → EFFECTIVE，仅 ADMIN/PM）")
    @PostMapping("/{id}/effective")
    public R<Void> effective(@PathVariable String id) {
        service.effective(id);
        return R.ok(null);
    }

    @Operation(summary = "停用（仅 ADMIN/PM）")
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok(null);
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.portal.PortalAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 门户账号管理（spec supplier-portal-account）：
 * 创建/启停 = ADMIN（SecurityConfig POST/PUT /api/proc/**），列表 = ADMIN/PM。
 */
@Tag(name = "门户账号管理")
@RestController
@RequestMapping("/api/proc/portal-accounts")
public class PortalAccountController {

    private final PortalAccountService service;

    public PortalAccountController(PortalAccountService service) {
        this.service = service;
    }

    @Operation(summary = "门户账号列表（可按供应商过滤）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String supplierId) {
        return R.ok(service.list(supplierId));
    }

    @Operation(summary = "创建门户账号（绑定供应商，ROLE_SUPPLIER）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "停用门户账号（立即失去门户访问）")
    @PutMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(service.disable(id));
    }

    @Operation(summary = "启用门户账号")
    @PutMapping("/{id}/enable")
    public R<Map<String, Object>> enable(@PathVariable String id) {
        return R.ok(service.enable(id));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.TempGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 敏感字段临时授权 API（add-perm-control-console，spec bi-temp-grant，≤24h 自动收回）。 */
@Tag(name = "BI-临时授权")
@RestController
@RequestMapping("/api/bi/temp-grants")
@RequiredArgsConstructor
public class BiTempGrantController {

    private final TempGrantService service;

    @Operation(summary = "申请查看明文（≤24h，ROLE_DATA_GOV 单签）")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        return R.ok(service.apply(payload));
    }

    @Operation(summary = "我的申请记录")
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> listMine(@RequestParam(required = false) String userName) {
        return R.ok(service.listMine(userName));
    }

    @Operation(summary = "全部记录（管理视角）")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> listAll(@RequestParam(required = false) String status) {
        return R.ok(service.listAll(status));
    }

    @Operation(summary = "主动收回")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> revoke(@PathVariable String id) {
        return R.ok(service.revoke(id));
    }

    @Operation(summary = "手动触发过期扫描（管理员）")
    @PostMapping("/expire-scan")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Integer> expireScan() {
        return R.ok(service.expireOverdue());
    }
}

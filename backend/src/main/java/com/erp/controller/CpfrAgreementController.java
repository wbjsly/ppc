package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.scm.CpfrAgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** CPFR 协同协议（spec cpfr-collaboration R1，菜单 12.8.1 协议 Tab）。 */
@Tag(name = "CPFR-协同协议")
@RestController
@RequestMapping("/api/scm/cpfr/agreements")
public class CpfrAgreementController {

    private final CpfrAgreementService agreementService;

    public CpfrAgreementController(CpfrAgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @Operation(summary = "协议分页")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(agreementService.page(current, size, keyword));
    }

    @Operation(summary = "协议详情（含缺失参数清单）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable String id) {
        return R.ok(agreementService.get(id));
    }

    @Operation(summary = "创建/更新协议")
    @PostMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(agreementService.save(payload));
    }

    @Operation(summary = "启用协同（BR-4.9-31 参数不全 422）")
    @PostMapping("/{id}/enable")
    public R<Map<String, Object>> enable(@PathVariable String id) {
        return R.ok(agreementService.enable(id));
    }

    @Operation(summary = "停用协同（手工，留痕）")
    @PostMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(agreementService.disable(id));
    }
}

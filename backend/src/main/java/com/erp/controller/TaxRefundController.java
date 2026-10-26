package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.TaxRefundApply;
import com.erp.service.fin.TaxRefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 留抵退税接口（M9-5-1 页内入口，BR-4.14-33 / S-4.14-15）。
 */
@RestController
@RequestMapping("/api/fin/tax-refunds")
@RequiredArgsConstructor
@Tag(name = "增值税留抵退税（M9-5-1）")
public class TaxRefundController {

    private final TaxRefundService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "留抵退税申请（BR-4.14-33 四条件逐项校验 + 允许退还公式）")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        return R.ok(service.apply(payload));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "提交 L2 审批（税务主管单节点）")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/register")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "税务机关状态登记（受理/退回补正+清单/核准）")
    public R<Map<String, Object>> register(@PathVariable String id,
                                           @RequestBody Map<String, Object> payload) {
        return R.ok(service.registerStatus(id, payload));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "退税款到账（一致自动核销+收款凭证；差异挂账核查 BR-4.14-45）")
    public R<Map<String, Object>> receive(@PathVariable String id,
                                          @RequestBody Map<String, Object> payload) {
        return R.ok(service.receive(id, payload));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申请分页")
    public R<Page<TaxRefundApply>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String period) {
        return R.ok(service.page(current, size, status, period));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "详情（含审批轨迹）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }
}

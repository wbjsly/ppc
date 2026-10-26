package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.InvoiceVoidApply;
import com.erp.service.fin.RedVoidService;
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
 * 发票作废接口（M9-4-2，spec red-invoice-void）。
 */
@RestController
@RequestMapping("/api/fin/invoice-void-applies")
@RequiredArgsConstructor
@Tag(name = "发票作废（M9-4-2）")
public class RedVoidController {

    private final RedVoidService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "作废申请（三条件校验 BR-4.14-24，不满足引导红冲）+ 提交审批")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        return R.ok(service.apply(payload));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "作废申请分页")
    public R<Page<InvoiceVoidApply>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "详情（申请 + 发票 + 审批轨迹）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.ExpiryReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 每日效期预警报告（4.10.1，spec expiry-management 需求①）。
 * 查询仅需认证；生成由每日调度驱动，另提供手工补生成（WAREHOUSE/ADMIN）。
 */
@Tag(name = "效期管理-效期预警")
@RestController
@RequestMapping("/api/inv/expiry-reports")
public class ExpiryReportController {

    private final ExpiryReportService service;

    public ExpiryReportController(ExpiryReportService service) {
        this.service = service;
    }

    @GetMapping("/history")
    @Operation(summary = "历史报告列表（时间倒序，三级计数）")
    public R<List<Map<String, Object>>> history(@RequestParam(defaultValue = "30") long size) {
        return R.ok(service.history(size));
    }

    @GetMapping("/detail")
    @Operation(summary = "按日报告详情（明细 + 较前报新增/解除）")
    public R<Map<String, Object>> detail(@RequestParam String reportDate) {
        Map<String, Object> d = service.detail(reportDate);
        return d == null ? R.ok(null) : R.ok(d);
    }

    @GetMapping("/today")
    @Operation(summary = "当日报告（未生成返回 null）")
    public R<Map<String, Object>> today() {
        Map<String, Object> d = service.detail(java.time.LocalDate.now().toString());
        return R.ok(d);
    }

    @org.springframework.web.bind.annotation.PostMapping("/generate")
    @Operation(summary = "手工补生成当日报告（WAREHOUSE/ADMIN，幂等覆盖）")
    public R<Map<String, Object>> generate() {
        return R.ok(service.generateDailyReport());
    }
}

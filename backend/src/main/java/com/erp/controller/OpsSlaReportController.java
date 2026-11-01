package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ops.OpsSlaReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 18.5 服务报告：月度报告生成/归档/发布 + 改进项台账。
 */
@Tag(name = "运维 SLA 报告")
@RestController
@RequestMapping("/api/ops/sla-reports")
public class OpsSlaReportController {

    private final OpsSlaReportService service;

    public OpsSlaReportController(OpsSlaReportService service) {
        this.service = service;
    }

    @Operation(summary = "生成月度报告（18.5.1，未达标自动生成改进项 BR-5.8-07）")
    @PostMapping("/{period}/generate")
    public R<Map<String, Object>> generate(@PathVariable String period) {
        return R.ok(service.generate(period));
    }

    @Operation(summary = "报告快照")
    @GetMapping("/{period}")
    public R<Map<String, Object>> report(@PathVariable String period) {
        return R.ok(service.report(period));
    }

    @Operation(summary = "归档（DRAFT→ARCHIVED）")
    @PostMapping("/{period}/archive")
    public R<Map<String, Object>> archive(@PathVariable String period) {
        return R.ok(service.archive(period));
    }

    @Operation(summary = "对外发布（未归档 422 阻断 BR-5.8-16）")
    @PostMapping("/{period}/publish")
    public R<Map<String, Object>> publish(@PathVariable String period,
                                          @RequestParam(required = false) String scope) {
        return R.ok(service.publish(period, scope));
    }

    @Operation(summary = "改进项台账（18.5.2 跟踪）")
    @GetMapping("/improvements")
    public R<List<Map<String, Object>>> improvements(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(service.improvements(status, limit));
    }
}

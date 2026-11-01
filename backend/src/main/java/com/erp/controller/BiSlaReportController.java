package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.BiSlaReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报表 SLA 服务报告（add-bi-sla-report，spec bi-sla-report，4.10 流程五）。
 * 14.9.1 月度报告（采集/生成/三要素/提交）+ 14.9.2 归档发布（发布/PDF）。
 * 与 16.9 sla-report-management（/api/system/sla-reports）完全隔离。
 */
@Tag(name = "报表SLA服务报告")
@RestController
@RequestMapping("/api/bi/sla-reports")
@RequiredArgsConstructor
public class BiSlaReportController {

    private final BiSlaReportService service;

    @Operation(summary = "五指标快照+近6月趋势")
    @GetMapping("/metrics")
    public R<Map<String, Object>> metrics(@RequestParam String period) {
        return R.ok(service.metrics(period));
    }

    @Operation(summary = "报告列表")
    @GetMapping("/reports")
    public R<List<Map<String, Object>>> listReports() {
        return R.ok(service.listReports());
    }

    @Operation(summary = "报告详情（含版本历史）")
    @GetMapping("/reports/{period}")
    public R<Map<String, Object>> getReport(@PathVariable String period) {
        return R.ok(service.getReport(period));
    }

    @Operation(summary = "采集五指标快照")
    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestParam String period) {
        return R.ok(service.collectMetrics(period));
    }

    @Operation(summary = "生成月度报告")
    @PostMapping("/reports/{period}/generate")
    public R<Map<String, Object>> generate(@PathVariable String period) {
        return R.ok(service.generateReport(period));
    }

    @Operation(summary = "编辑未达标三要素")
    @PutMapping("/reports/{period}/root-cause")
    public R<Void> saveRootCause(@PathVariable String period,
                                 @RequestBody List<Map<String, Object>> items) {
        service.saveRootCause(period, items);
        return R.ok();
    }

    @Operation(summary = "编辑改进措施与下月计划")
    @PutMapping("/reports/{period}/improve")
    public R<Void> saveImprove(@PathVariable String period,
                               @RequestBody Map<String, Object> improve) {
        service.saveImprove(period, improve);
        return R.ok();
    }

    @Operation(summary = "提交三方审核（三要素齐备否则 422）")
    @PostMapping("/reports/{period}/submit")
    public R<Map<String, Object>> submit(@PathVariable String period) {
        return R.ok(service.submit(period));
    }

    @Operation(summary = "归档后修改 → 新版本")
    @PostMapping("/reports/{period}/new-version")
    public R<Map<String, Object>> newVersion(@PathVariable String period) {
        return R.ok(service.newVersion(period));
    }

    @Operation(summary = "对外发布（未归档 422 硬阻断）")
    @PostMapping("/reports/{period}/publish")
    public R<Map<String, Object>> publish(@PathVariable String period) {
        return R.ok(service.publish(period));
    }

    @Operation(summary = "发布版 PDF（仅 ARCHIVED/PUBLISHED）")
    @GetMapping("/reports/{period}/pdf")
    public R<Map<String, Object>> pdf(@PathVariable String period) {
        return R.ok(service.pdf(period));
    }
}

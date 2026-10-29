package com.erp.controller;

import com.erp.common.R;
import com.erp.service.SlaReportService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务报告控制台（16.9，spec sla-report-management）：月度报告生成/归档/发布/下载。
 * 限 ADMIN（SecurityConfig 细规则）。
 */
@RestController
@RequestMapping("/api/system/sla-reports")
@RequiredArgsConstructor
public class SlaReportController {

    private final SlaReportService slaReportService;

    @Operation(summary = "报告列表（period/status 过滤，分页倒序）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String period,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return R.ok(slaReportService.list(period, status, page, size));
    }

    @Operation(summary = "按月生成/重生成（YYYY-MM；已归档 422）")
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        return R.ok(slaReportService.generateMonth(
                body.get("period") == null ? null : String.valueOf(body.get("period"))));
    }

    @Operation(summary = "月度 sweep（上月补缺，任意已存在跳过）")
    @PostMapping("/sweep")
    public R<Map<String, Object>> sweep() {
        return R.ok(slaReportService.sweepGenerate());
    }

    @Operation(summary = "归档（DRAFT → ARCHIVED，版本锁定）")
    @PostMapping("/{id}/archive")
    public R<Void> archive(@PathVariable String id) {
        slaReportService.archive(id);
        return R.ok(null);
    }

    @Operation(summary = "对外发布（非 ARCHIVED → 422 + 违规留痕，BR-5.6-16）")
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable String id) {
        slaReportService.publish(id);
        return R.ok(null);
    }

    @Operation(summary = "CSV 下载（仅 ARCHIVED/PUBLISHED；固定列序 + BOM）")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        Map<String, Object> out = slaReportService.download(id);
        byte[] csv = (byte[]) out.get("content");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + out.get("filename") + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.CostReportService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 期间成本报表（M8-10-3，change add-cost-accounting）。
 * 只读聚合端点：JSON 汇总+明细 / CSV 导出 / 多期趋势（design D3/D4）。
 */
@RestController
@RequestMapping("/api/fin/cost-report")
@RequiredArgsConstructor
public class CostReportController {

    private final CostReportService service;

    @Operation(summary = "期间成本报表（summary 汇总卡 + rows 工单明细含超容差红标）")
    @GetMapping
    public R<Map<String, Object>> report(@RequestParam String period,
                                         @RequestParam(required = false) String productCode,
                                         @RequestParam(required = false) String reportStatus) {
        return R.ok(service.report(period, productCode, reportStatus));
    }

    @Operation(summary = "CSV 导出（与报表同源查询，UTF-8 BOM）")
    @GetMapping(params = "format=csv")
    public ResponseEntity<byte[]> csv(@RequestParam String period,
                                      @RequestParam(required = false) String productCode,
                                      @RequestParam(required = false) String reportStatus) {
        byte[] body = service.csv(period, productCode, reportStatus).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cost-report-" + period + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "多期趋势与构成（按期间聚合，单期如实返回单元素）")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend() {
        return R.ok(service.trend());
    }
}

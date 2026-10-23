package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.DepreciationMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 财务域折旧监控（M8-6-1/8.6-2，add-depreciation-monitoring）。
 * 全 GET 只读：期间状态视图 + 明细下钻 + 残值预警实时扫描；无计提触发入口（design D4/D5）。
 */
@Tag(name = "折旧监控")
@RestController
@RequestMapping("/api/fin/depreciation-monitor")
public class DepreciationMonitorController {

    private final DepreciationMonitorService service;

    public DepreciationMonitorController(DepreciationMonitorService service) {
        this.service = service;
    }

    @Operation(summary = "期间折旧状态列表（倒序；未计提期间 NOT_RUN 红字标识）")
    @GetMapping("/runs")
    public R<List<Map<String, Object>>> periodStatuses() {
        return R.ok(service.periodStatuses());
    }

    @Operation(summary = "期间计提明细下钻（逐卡原值/当期折旧/累计/NBV/预警标记）")
    @GetMapping("/runs/{period}/details")
    public R<Map<String, Object>> periodDetails(@PathVariable String period) {
        return R.ok(service.periodDetails(period));
    }

    @Operation(summary = "残值预警实时扫描（C-4.6-04，当前时点不落库）")
    @GetMapping("/residual-alerts")
    public R<Map<String, Object>> residualAlerts() {
        return R.ok(service.residualAlerts());
    }
}

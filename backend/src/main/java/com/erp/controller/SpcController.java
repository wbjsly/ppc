package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.SpcAlert;
import com.erp.entity.qms.SpcSample;
import com.erp.service.qms.SpcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SPC 过程控制（6.10.1 特性监控 / 6.10.2 趋势预警，spec spc-monitoring）。
 * 控制限：≥25 组 X̄±3σ，否则规格折算标「预控制」；三规则告警 + 处置闭环。
 */
@Tag(name = "SPC 过程控制")
@RestController
@RequestMapping("/api/qms/spc")
public class SpcController {

    private final SpcService service;

    public SpcController(SpcService service) {
        this.service = service;
    }

    @Operation(summary = "采样组录入（自动算控制限 + 三规则评估）")
    @PostMapping("/samples")
    public R<SpcSample> recordSample(@RequestBody Map<String, Object> body) {
        return R.ok(service.recordSample(body));
    }

    @Operation(summary = "特性采样序列（趋势图数据）")
    @GetMapping("/samples")
    public R<List<SpcSample>> samples(@RequestParam String charCode,
                                      @RequestParam(defaultValue = "100") int limit) {
        return R.ok(service.samples(charCode, limit));
    }

    @Operation(summary = "告警分页")
    @GetMapping("/alerts")
    public R<Page<SpcAlert>> alerts(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String charCode,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.alerts(current, size, charCode, status));
    }

    @Operation(summary = "告警处置闭环（OPEN → HANDLED）")
    @PostMapping("/alerts/{id}/handle")
    public R<SpcAlert> handle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object result = body.get("result");
        return R.ok(service.handleAlert(id, result == null ? null : String.valueOf(result)));
    }

    @Operation(summary = "趋势汇总（各特性最新点/控制限/OPEN 告警数）")
    @GetMapping("/trends")
    public R<List<Map<String, Object>>> trends() {
        return R.ok(service.trends());
    }
}

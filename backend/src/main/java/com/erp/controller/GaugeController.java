package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.Gauge;
import com.erp.entity.qms.GaugeCalibration;
import com.erp.entity.qms.SuspectLot;
import com.erp.service.qms.GaugeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 计量器具与校准（6.9.1 校准计划 / 6.9.2 校准执行 / 6.9.3 到期预警，spec gauge-calibration）。
 * 校准 FAIL → 停用 + 可疑批次反向追溯；未评估可疑批次阻断放行（C-4.12-12）。
 */
@Tag(name = "器具校准")
@RestController
@RequestMapping("/api/qms/gauges")
public class GaugeController {

    private final GaugeService service;

    public GaugeController(GaugeService service) {
        this.service = service;
    }

    @Operation(summary = "台账分页")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "详情（台账 + 校准历史 + 可疑批次）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "校准历史")
    @GetMapping("/{id}/calibrations")
    public R<List<GaugeCalibration>> calibrations(@PathVariable String id) {
        return R.ok(service.calibrations(id));
    }

    @Operation(summary = "台账新增/编辑（CTQ 周期 ≤12 月）")
    @PostMapping
    public R<Gauge> save(@RequestBody Map<String, Object> body) {
        return R.ok(service.save(body));
    }

    @Operation(summary = "校准执行（FAIL → 停用 + 可疑批次追溯）")
    @PostMapping("/{id}/calibrations")
    public R<GaugeCalibration> calibrate(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(service.calibrate(id, body));
    }

    @Operation(summary = "到期预警（30 天/7 天分组 + 扫描）")
    @GetMapping("/warnings")
    public R<Map<String, Object>> warnings() {
        return R.ok(service.warningList());
    }

    @Operation(summary = "到期扫描（测试与运维手动触发）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        return R.ok(service.sweepDue());
    }

    @Operation(summary = "可疑批次清单（评估超期标记）")
    @GetMapping("/suspects")
    public R<List<Map<String, Object>>> suspects(@RequestParam(required = false) String gaugeCode) {
        return R.ok(service.suspectLots(gaugeCode));
    }

    @Operation(summary = "可疑批次评估（结论必填；未评估阻断放行）")
    @PostMapping("/suspects/{id}/eval")
    public R<SuspectLot> evalSuspect(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object conclusion = body.get("conclusion");
        return R.ok(service.evalSuspect(id, conclusion == null ? null : String.valueOf(conclusion)));
    }

    @Operation(summary = "器具使用追溯（时间窗反查检验记录）")
    @GetMapping("/trace")
    public R<List<Map<String, Object>>> trace(@RequestParam String gaugeCode,
                                              @RequestParam(required = false) String fromDate) {
        return R.ok(service.traceLots(gaugeCode, fromDate));
    }
}

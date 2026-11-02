package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotTelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测点时序查询与注入（add-iot-mobile 13.6.2/13.6.3）。
 * 注入口供演示/测试造数（IOT_SIM_ENABLED=OFF 时的唯一数据源）。
 */
@Tag(name = "移动物联-测点时序")
@RestController
@RequestMapping("/api/iot/telemetry")
public class TelemetryController {

    private final IotTelemetryService service;

    public TelemetryController(IotTelemetryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "时序查询（设备+测点+时间窗，自动裁剪保留窗）")
    public R<List<Map<String, Object>>> query(@RequestParam String deviceNo,
                                              @RequestParam(required = false) String pointCode,
                                              @RequestParam(required = false) String start,
                                              @RequestParam(required = false) String end,
                                              @RequestParam(required = false) Integer limit) {
        return R.ok(service.query(deviceNo, pointCode, start, end, limit));
    }

    @GetMapping("/suspect")
    @Operation(summary = "可疑数据清单（QUALITY=SUSPECT 过滤展示）")
    public R<List<Map<String, Object>>> suspect(@RequestParam(required = false) String deviceNo,
                                                @RequestParam(required = false) Integer limit) {
        return R.ok(service.suspectList(deviceNo, limit));
    }

    @GetMapping("/latest")
    @Operation(summary = "最新测点（看板消费）")
    public R<Map<String, Object>> latest(@RequestParam String deviceNo,
                                         @RequestParam String pointCode) {
        return R.ok(service.latest(deviceNo, pointCode));
    }

    @PostMapping("/inject")
    @Operation(summary = "手动注入测点（演示/测试造数，带 batchTag）")
    public R<Integer> inject(@RequestBody Map<String, Object> body) {
        return R.ok(service.inject(body));
    }

    @PostMapping("/maintain")
    @Operation(summary = "归档维护（90 天原始清理 + 分区滚动观测）")
    public R<Map<String, Object>> maintain() {
        return R.ok(service.maintain());
    }
}

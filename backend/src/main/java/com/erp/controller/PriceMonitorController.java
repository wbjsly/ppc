package com.erp.controller;

import com.erp.common.R;
import com.erp.security.IntfGuard;
import com.erp.service.bi.PriceMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 2.9.3 价格监测工作台（spec price-monitoring：处置状态机/阈值配置/协议价偏离/比价异常）。 */
@Tag(name = "价格监测")
@RestController
@RequestMapping("/api/bi/monitor")
public class PriceMonitorController {

    private final PriceMonitorService service;

    public PriceMonitorController(PriceMonitorService service) {
        this.service = service;
    }

    @Operation(summary = "异动清单（筛选）")
    @GetMapping("/alerts")
    public R<Map<String, Object>> alerts(@RequestParam(required = false) String monthTag,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String itemCode) {
        return R.ok(service.alertList(monthTag, status, itemCode));
    }

    @Operation(summary = "异动处置（OPEN→HANDLED/IGNORED，留痕不可篡改）")
    @PostMapping("/alerts/{id}/handle")
    public R<Map<String, Object>> handle(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        Object action = body.get("action");
        Object note = body.get("note");
        return R.ok(service.handle(id, action == null ? null : String.valueOf(action),
                note == null ? null : String.valueOf(note)));
    }

    @Operation(summary = "阈值参数（含调整历史）")
    @GetMapping("/threshold/{key}")
    public R<Map<String, Object>> threshold(@PathVariable String key) {
        return R.ok(service.threshold(key, "10"));
    }

    @Operation(summary = "阈值在线调整（高危：仅 ADMIN；留痕、对新判定生效）")
    @PostMapping("/threshold/{key}")
    public R<Map<String, Object>> setThreshold(@PathVariable String key,
                                               @RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("监测阈值调整");
        Object v = body.get("value");
        Object remark = body.get("remark");
        return R.ok(service.setThreshold(key, v == null ? null : String.valueOf(v),
                remark == null ? null : String.valueOf(remark)));
    }

    @Operation(summary = "协议价偏离清单（超容差高亮 + 价控日志关联，只读）")
    @GetMapping("/deviation")
    public R<Map<String, Object>> deviation() {
        return R.ok(service.deviationList());
    }

    @Operation(summary = "比价异常清单（BR-4.2-12 偏离均值 >20%，只读）")
    @GetMapping("/quote-anomalies")
    public R<Map<String, Object>> quoteAnomalies() {
        return R.ok(service.quoteAnomalies());
    }
}

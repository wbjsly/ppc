package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotPredictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预测维护（add-iot-mobile 13.8，FR-4.11-8-x / BR-4.11-48~53）。
 * 幂等建单（告警事件 ID 幂等键）/ 误报率统计 / 工单闭环回写。
 */
@Tag(name = "移动物联-预测维护")
@RestController
@RequestMapping("/api/iot/predictive")
public class PredictMaintController {

    private final IotPredictService service;

    public PredictMaintController(IotPredictService service) {
        this.service = service;
    }

    @GetMapping("/trend-candidates")
    @Operation(summary = "13.8.1 待评估趋势告警（未建单）")
    public R<List<Map<String, Object>>> trendCandidates() {
        return R.ok(service.trendCandidates());
    }

    @PostMapping("/{eventNo}/create-wo")
    @Operation(summary = "13.8.1 告警联动幂等建单（重复 → duplicate:true 409 语义）")
    public R<Map<String, Object>> createWo(@PathVariable String eventNo) {
        Map<String, Object> r = service.createWoIdempotent(eventNo);
        if (Boolean.TRUE.equals(r.get("duplicate"))) {
            return R.fail(409, "该告警已建单（幂等）：" + r.get("woNo"));
        }
        return R.ok(r);
    }

    @GetMapping("/false-positive-rate")
    @Operation(summary = "13.8.2 误报率统计（30 天窗口，>20% 提示复核阈值）")
    public R<Map<String, Object>> falsePositiveRate() {
        return R.ok(service.falsePositiveRate());
    }

    @PostMapping("/{eventNo}/false-positive")
    @Operation(summary = "误报标记（工单判定非故障）")
    public R<Map<String, Object>> markFalsePositive(@PathVariable String eventNo,
                                                    @RequestBody Map<String, Boolean> body) {
        return R.ok(service.markFalsePositive(eventNo,
                Boolean.TRUE.equals(body.get("fp"))));
    }

    @PostMapping("/wo-closed/{woNo}")
    @Operation(summary = "工单关闭 → 告警事件闭环回写")
    public R<Map<String, Object>> onWoClosed(@PathVariable String woNo) {
        return R.ok(service.onWoClosed(woNo));
    }
}

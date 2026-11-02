package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotAlarmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 阈值告警与联动闭环（add-iot-mobile 13.6.3，FR-4.11-5-3）。
 * 多级阈值/趋势判定/5min 合并/确认-处理-关闭/超时升级/级别联动。
 */
@Tag(name = "移动物联-阈值告警")
@RestController
@RequestMapping("/api/iot/alarms")
public class IotAlarmController {

    private final IotAlarmService service;

    public IotAlarmController(IotAlarmService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(summary = "阈值判定（多级阈值 + 趋势斜率 + 5min 合并）")
    public R<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body) {
        Object val = body.get("val");
        java.math.BigDecimal v = val == null ? null : new java.math.BigDecimal(String.valueOf(val));
        return R.ok(service.evaluate(String.valueOf(body.get("deviceNo")),
                String.valueOf(body.get("pointCode")), v));
    }

    @GetMapping
    @Operation(summary = "告警列表（状态/级别/设备筛选）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String alertLevel,
                                             @RequestParam(required = false) String deviceNo) {
        return R.ok(service.list(status, alertLevel, deviceNo));
    }

    @PostMapping("/{eventNo}/confirm")
    @Operation(summary = "确认（闭环第一步）")
    public R<Map<String, Object>> confirm(@PathVariable String eventNo,
                                          @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.confirm(eventNo, body == null ? null : body.get("remark")));
    }

    @PostMapping("/{eventNo}/processing")
    @Operation(summary = "处理中")
    public R<Map<String, Object>> processing(@PathVariable String eventNo,
                                             @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.processing(eventNo, body == null ? null : body.get("remark")));
    }

    @PostMapping("/{eventNo}/close")
    @Operation(summary = "关闭（闭环终态）")
    public R<Map<String, Object>> close(@PathVariable String eventNo,
                                        @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.close(eventNo, body == null ? null : body.get("remark")));
    }

    @PostMapping("/{eventNo}/linkage")
    @Operation(summary = "级别联动执行（L1 停机打点 / L2 冻结 / L3 日志）")
    public R<Map<String, Object>> linkage(@PathVariable String eventNo) {
        return R.ok(service.linkage(eventNo));
    }

    @PostMapping("/scan-escalate")
    @Operation(summary = "超时未确认升级扫描（30min 升级上级）")
    public R<Map<String, Object>> scanEscalate() {
        return R.ok(service.scanEscalate());
    }
}

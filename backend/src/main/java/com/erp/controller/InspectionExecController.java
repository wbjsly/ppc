package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotInspectService;
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
 * 设备巡检执行（add-iot-mobile 13.3，S-4.11-06 扫码签到/逐项点检/异常标记/拍照留证）。
 */
@Tag(name = "移动物联-设备巡检")
@RestController
@RequestMapping("/api/iot/inspections")
public class InspectionExecController {

    private final IotInspectService service;

    public InspectionExecController(IotInspectService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    @Operation(summary = "巡检任务批量生成（源=QMS 巡检计划到期）")
    public R<Map<String, Object>> generate() {
        return R.ok(service.generateFromPlans());
    }

    @PostMapping
    @Operation(summary = "手动创建巡检任务（设备+点检项模板）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @PostMapping("/{id}/checkin")
    @Operation(summary = "扫码设备签到（设备不一致 L1 拒绝）")
    public R<Map<String, Object>> checkin(@PathVariable String id,
                                          @RequestParam(required = false) String deviceNo) {
        return R.ok(service.checkin(id, deviceNo));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "逐点检项录入提交（异常须拍照留证；异常项联动异常上报）")
    public R<Map<String, Object>> submit(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(service.submit(id, (String) body.get("itemsJson"),
                (String) body.get("photoUrls")));
    }

    @GetMapping
    @Operation(summary = "巡检记录查询")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String deviceNo,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(deviceNo, status));
    }

    @PostMapping("/scan-overdue")
    @Operation(summary = "超时漏检提醒扫描")
    public R<Map<String, Object>> scanOverdue() {
        return R.ok(service.scanOverdue());
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * IoT 设备接入（add-iot-mobile 13.6.1，FR-4.11-5-1 / C-4.11-18）。
 * 管理口（注册/告警规则）= ROLE_IOT_ADMIN（SecurityConfig）。
 */
@Tag(name = "移动物联-设备接入")
@RestController
@RequestMapping("/api/iot/devices")
public class DeviceController {

    private final IotDeviceService service;

    public DeviceController(IotDeviceService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @Operation(summary = "设备注册（协议/采集参数/告警规则，证书签发）")
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return R.ok(service.register(body));
    }

    @PostMapping("/{deviceNo}/connect-test")
    @Operation(summary = "连接测试（网关握手，证书无效 L1 拒绝接入）")
    public R<Map<String, Object>> connectTest(@PathVariable String deviceNo) {
        return R.ok(service.connectTest(deviceNo));
    }

    @PostMapping("/{deviceNo}/heartbeat")
    @Operation(summary = "心跳打点")
    public R<Map<String, Object>> heartbeat(@PathVariable String deviceNo) {
        return R.ok(service.heartbeat(deviceNo));
    }

    @GetMapping
    @Operation(summary = "设备列表")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String deviceType) {
        return R.ok(service.list(keyword, status, deviceType));
    }

    @GetMapping("/{deviceNo}")
    @Operation(summary = "设备详情")
    public R<Map<String, Object>> detail(@PathVariable String deviceNo) {
        return R.ok(service.detail(deviceNo));
    }

    @GetMapping("/status-distribution")
    @Operation(summary = "13.6.4 状态分布（运行/停机/故障/维护）")
    public R<Map<String, Object>> statusDistribution() {
        return R.ok(service.statusDistribution());
    }

    @PutMapping("/{deviceNo}/alert-rule")
    @Operation(summary = "告警规则配置（多级阈值/趋势）")
    public R<Map<String, Object>> updateAlertRule(@PathVariable String deviceNo,
                                                  @RequestBody Map<String, String> body) {
        return R.ok(service.updateAlertRule(deviceNo, body.get("alertRuleJson")));
    }
}

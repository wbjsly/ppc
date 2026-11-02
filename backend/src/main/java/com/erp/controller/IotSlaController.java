package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotSlaService;
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
 * 移动/IoT SLA 月度报告（add-iot-mobile 13.9，FR-4.11-6-x）。
 * 发布口 = ROLE_IOT_ADMIN（SecurityConfig 13 域规则外的读写由登录+服务层把关）。
 */
@Tag(name = "移动物联-服务报告")
@RestController
@RequestMapping("/api/iot/sla-reports")
public class IotSlaController {

    private final IotSlaService service;

    public IotSlaController(IotSlaService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    @Operation(summary = "13.9 月度报告生成（五指标真实统计，DRAFT）")
    public R<Map<String, Object>> generate(@RequestParam String period) {
        return R.ok(service.generate(period));
    }

    @PostMapping("/{period}/archive")
    @Operation(summary = "归档（自动推送订阅人）")
    public R<Map<String, Object>> archive(@PathVariable String period) {
        return R.ok(service.archive(period));
    }

    @PostMapping("/{period}/publish")
    @Operation(summary = "发布（未归档 422 阻断，C-05）")
    public R<Map<String, Object>> publish(@PathVariable String period) {
        return R.ok(service.publish(period));
    }

    @GetMapping
    @Operation(summary = "报告列表")
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    @PostMapping("/subscribe")
    @Operation(summary = "13.9.2 报告订阅（归档自动推送）")
    public R<Map<String, Object>> subscribe(@RequestBody Map<String, String> body) {
        return R.ok(service.subscribe(body.get("subscriber")));
    }
}

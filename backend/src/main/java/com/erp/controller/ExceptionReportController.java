package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotExceptionService;
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
 * 移动端异常上报与联动（add-iot-mobile 13.4，FR-4.11-3-x）。
 * 三类联动：质量冻结/设备故障建单/安全停机打点；IoT 来源现场确认门禁。
 */
@Tag(name = "移动物联-异常上报")
@RestController
@RequestMapping("/api/iot/exceptions")
public class ExceptionReportController {

    private final IotExceptionService service;

    public ExceptionReportController(IotExceptionService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "13.4 异常上报（质量/设备/安全三类 + 联动编排 + 同班次合并）")
    public R<Map<String, Object>> report(@RequestBody Map<String, Object> body) {
        return R.ok(service.report(body));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "IoT 自动上报现场确认（确认后触发被门禁联动）")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    @PostMapping("/{id}/dispose")
    @Operation(summary = "处置闭环（关闭 + 告警事件回写）")
    public R<Map<String, Object>> dispose(@PathVariable String id,
                                          @RequestBody Map<String, String> body) {
        return R.ok(service.dispose(id, body.get("result"), body.get("remark")));
    }

    @GetMapping
    @Operation(summary = "异常单查询")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String excType) {
        return R.ok(service.list(status, excType));
    }

    @PostMapping("/scan-escalate")
    @Operation(summary = "30min 未响应升级扫描（BR-4.11-22/27）")
    public R<Map<String, Object>> scanEscalate() {
        return R.ok(service.scanEscalate());
    }
}

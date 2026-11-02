package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotTerminalService;
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
 * 移动终端注册与签审（add-iot-mobile，C-4.11-18 未注册拒绝/双因子）。
 * 管理口（注册/吊销/解锁）= ROLE_IOT_ADMIN；签审校验 = 登录用户。
 */
@Tag(name = "移动物联-终端管理")
@RestController
@RequestMapping("/api/iot/terminals")
public class TerminalController {

    private final IotTerminalService service;

    public TerminalController(IotTerminalService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @Operation(summary = "终端注册（签发设备证书，绑定操作员）")
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return R.ok(service.register(body));
    }

    @PutMapping("/{deviceCode}/bind")
    @Operation(summary = "绑定操作员")
    public R<Map<String, Object>> bind(@PathVariable String deviceCode,
                                       @RequestParam String userId) {
        return R.ok(service.bind(deviceCode, userId));
    }

    @PutMapping("/{deviceCode}/unbind")
    @Operation(summary = "解绑操作员")
    public R<Map<String, Object>> unbind(@PathVariable String deviceCode) {
        return R.ok(service.unbind(deviceCode));
    }

    @PostMapping("/verify-sign")
    @Operation(summary = "签审双因子校验（注册态 + 二次密码；连续 3 次失败锁定）")
    public R<Map<String, Object>> verifySign(@RequestBody Map<String, String> body) {
        return R.ok(service.verifySign(body));
    }

    @PostMapping("/{deviceCode}/unlock")
    @Operation(summary = "管理员解锁（留痕）")
    public R<Map<String, Object>> unlock(@PathVariable String deviceCode,
                                         @RequestParam String reason) {
        return R.ok(service.unlock(deviceCode, reason));
    }

    @PostMapping("/{deviceCode}/revoke-cert")
    @Operation(summary = "证书吊销")
    public R<Map<String, Object>> revokeCert(@PathVariable String deviceCode) {
        return R.ok(service.revokeCert(deviceCode));
    }

    @GetMapping
    @Operation(summary = "终端列表")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(keyword, status));
    }

    @GetMapping("/{deviceCode}")
    @Operation(summary = "终端详情")
    public R<Map<String, Object>> detail(@PathVariable String deviceCode) {
        return R.ok(service.detail(deviceCode));
    }
}

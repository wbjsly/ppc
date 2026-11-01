package com.erp.controller;

import com.erp.common.R;
import com.erp.service.intf.SsoService;
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
 * 内部 SSO（本系统作 IdP，add-intf-portal-menus，spec interface-sso，菜单 15.2.3）。
 * 兄弟系统注册/断言签发/验签元数据；注册与状态变更高危动作限 ADMIN（服务层二次校验）。
 */
@Tag(name = "SSO 单点登录（内部 IdP）")
@RestController
@RequestMapping("/api/intf/sso")
public class SsoController {

    private final SsoService service;

    public SsoController(SsoService service) {
        this.service = service;
    }

    @Operation(summary = "注册兄弟系统（ADMIN；APP_ID 唯一）")
    @PostMapping("/apps")
    public R<Map<String, Object>> registerApp(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerApp(payload));
    }

    @Operation(summary = "兄弟系统列表")
    @GetMapping("/apps")
    public R<List<Map<String, Object>>> listApps() {
        return R.ok(service.listApps());
    }

    @Operation(summary = "停用/启用兄弟系统（ADMIN）")
    @PutMapping("/apps/{appId}/status")
    public R<Void> changeAppStatus(@PathVariable String appId, @RequestParam String status) {
        service.changeAppStatus(appId, status);
        return R.ok();
    }

    @Operation(summary = "签发 SSO 断言（限定 audience 短令牌；停用拒签）")
    @PostMapping("/assertion")
    public R<Map<String, Object>> issueAssertion(@RequestParam String appId) {
        return R.ok(service.issueAssertion(appId));
    }

    @Operation(summary = "IdP 验签元数据")
    @GetMapping("/metadata")
    public R<Map<String, Object>> metadata() {
        return R.ok(service.metadata());
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.scm.PartnerActivateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 伙伴激活匿名端点（add-partner-onboarding design D4，FR-4.9-1-4 / BR-4.9-11）。
 * SecurityConfig permitAll：token 一次性/过期/重复使用/密码复杂度校验全部在服务层。
 */
@Tag(name = "伙伴激活（匿名）")
@RestController
@RequestMapping("/api/public/partner-activate")
public class PublicPartnerActivateController {

    private final PartnerActivateService service;

    public PublicPartnerActivateController(PartnerActivateService service) {
        this.service = service;
    }

    @Operation(summary = "校验 token（脱敏回显企业名+邮箱域）")
    @GetMapping("/{token}")
    public R<Map<String, Object>> verify(@PathVariable String token) {
        return R.ok(service.verify(token));
    }

    @Operation(summary = "激活：设置密码（复杂度校验）即启用账号")
    @PostMapping("/{token}/activate")
    public R<Map<String, Object>> activate(@PathVariable String token,
                                           @RequestBody Map<String, Object> payload) {
        String password = payload.get("password") == null ? null
                : String.valueOf(payload.get("password"));
        return R.ok(service.activate(token, password));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.scm.PartnerChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 伙伴通道配置（add-partner-onboarding，菜单 12.1.3，design D5）。
 * 配置态唯一写入口；SecurityConfig /api/proc/partner-channels/** 先于兜底（INTF_OPS/ADMIN）。
 * 运行态（熔断/探测/吊销）留 2.8.3 interface-console。
 */
@Tag(name = "伙伴通道配置")
@RestController
@RequestMapping("/api/proc/partner-channels")
public class PartnerChannelController {

    private final PartnerChannelService service;

    public PartnerChannelController(PartnerChannelService service) {
        this.service = service;
    }

    @Operation(summary = "通道列表（partnerCode 可选）")
    @GetMapping
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String partnerCode) {
        return R.ok(service.list(partnerCode));
    }

    @Operation(summary = "创建通道（默认 SANDBOX；PROD 切换走放行会签）")
    @PostMapping
    public R<Map<String, Object>> createChannel(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createChannel(payload));
    }

    @Operation(summary = "生成 API Key+Secret（明文仅本次返回，落库加盐哈希）")
    @PostMapping("/keys")
    public R<Map<String, Object>> generateKey(@RequestBody Map<String, Object> payload) {
        return R.ok(service.generateKey(payload));
    }

    @Operation(summary = "更新限流档位（STRATEGIC 500/NORMAL 100/NEW 30）")
    @PutMapping("/{id}/rate-tier")
    public R<Map<String, Object>> updateRateTier(@PathVariable String id,
                                                 @RequestBody Map<String, Object> payload) {
        String tier = payload.get("rateTier") == null ? null
                : String.valueOf(payload.get("rateTier"));
        return R.ok(service.updateRateTier(id, tier));
    }

    @Operation(summary = "证书指纹与有效期更新（登记台账）")
    @PutMapping("/{id}/cert")
    public R<Map<String, Object>> updateCert(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateCert(id, payload));
    }
}

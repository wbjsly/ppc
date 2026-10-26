package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.TaxPreference;
import com.erp.service.fin.TaxPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 税收优惠备案管理（9.6.2，tax-preference-register）。
 */
@Tag(name = "税收优惠备案")
@RestController
@RequestMapping("/api/fin/tax-preferences")
@RequiredArgsConstructor
public class TaxPreferenceController {

    private final TaxPreferenceService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "登记优惠备案（S-4.14-11，重叠期间 422）")
    public R<TaxPreference> register(@RequestBody Map<String, Object> payload) {
        return R.ok(service.register(payload));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "备案列表（惰性过期判定 + 临期标注）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String prefType) {
        return R.ok(service.list(status, prefType));
    }

    @GetMapping("/warnings")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "到期预警清单（BR-4.14-06：90 天临期 + 冻结/终止）")
    public R<Map<String, Object>> warnings() {
        return R.ok(service.warnings());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "详情（含续期留痕）")
    public R<TaxPreference> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PutMapping("/{id}/renew")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "续期登记（BR-4.14-06，原期间/新期间/依据全程留痕）")
    public R<TaxPreference> renew(@PathVariable String id,
                                  @RequestBody Map<String, Object> payload) {
        return R.ok(service.renew(id, payload));
    }

    @PutMapping("/{id}/freeze")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "手工冻结（条件不满足，原因留痕）")
    public R<TaxPreference> freeze(@PathVariable String id,
                                   @RequestBody Map<String, String> body) {
        return R.ok(service.freeze(id, body.get("reason")));
    }

    @PutMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "政策废止终止（补税风险评估说明必填留痕）")
    public R<TaxPreference> revoke(@PathVariable String id,
                                   @RequestBody Map<String, String> body) {
        return R.ok(service.revoke(id, body.get("revokeDate"), body.get("riskAssessNote")));
    }
}

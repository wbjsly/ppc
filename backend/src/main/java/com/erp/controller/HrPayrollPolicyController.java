package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrSocialPolicy;
import com.erp.service.hr.HrSocialInsuranceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 社保公积金政策与扣缴（payroll-calculation 10.5.1）。 */
@Tag(name = "社保扣缴")
@RestController
@RequestMapping("/api/hr/payroll/social")
@RequiredArgsConstructor
public class HrPayrollPolicyController {

    private final HrSocialInsuranceService socialService;

    @GetMapping("/policies")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "政策清单（城市×年度×险种）")
    public R<List<HrSocialPolicy>> policies(@RequestParam(required = false) String city,
                                            @RequestParam(required = false) Integer year) {
        return R.ok(socialService.policyList(city, year));
    }

    @PostMapping("/policies")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "维护政策行（upsert 城市×年度×险种）")
    public R<HrSocialPolicy> savePolicy(@RequestBody Map<String, Object> payload) {
        return R.ok(socialService.savePolicy(payload));
    }

    @PostMapping("/policies/copy-year")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "年度复制（7 月基数调整后微调）")
    public R<Map<String, Object>> copyYear(@RequestBody Map<String, Object> payload) {
        Object from = payload.get("fromYear");
        Object to = payload.get("toYear");
        return R.ok(socialService.copyYear(
                from == null ? null : Integer.parseInt(String.valueOf(from)),
                to == null ? null : Integer.parseInt(String.valueOf(to))));
    }

    @GetMapping("/calc")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "扣缴试算（属地匹配封顶保底；未配置 422 L1）")
    public R<Map<String, Object>> calc(@RequestParam String empId, @RequestParam int year) {
        return R.ok(socialService.calc(empId, year));
    }

    @PostMapping("/july-scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "6 月基数调整提醒扫描（当年政策缺失城市）")
    public R<Map<String, Object>> julyScan() {
        return R.ok(Map.of("reminded", socialService.julyBaseAdjustScan()));
    }
}

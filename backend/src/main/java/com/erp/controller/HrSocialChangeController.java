package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrSocialChange;
import com.erp.service.hr.HrSocialChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 社保增减员/转移单（social-insurance-ops，M10-5.1 页签消费）。 */
@Tag(name = "社保增减员")
@RestController
@RequestMapping("/api/hr/social-changes")
@RequiredArgsConstructor
public class HrSocialChangeController {

    private final HrSocialChangeService socialChangeService;

    @GetMapping
    @Operation(summary = "单据清单（待申报=STATUS 空查全部，按类型/状态过滤）")
    public R<List<HrSocialChange>> list(@RequestParam(required = false) String empId,
                                        @RequestParam(required = false) String chgType,
                                        @RequestParam(required = false) String status) {
        return R.ok(socialChangeService.list(empId, chgType, status));
    }

    @PostMapping
    @Operation(summary = "手工补开（覆盖漏办与特殊场景）")
    public R<HrSocialChange> manualOpen(@RequestBody Map<String, Object> payload) {
        return R.ok(socialChangeService.manualOpen(payload));
    }

    @PostMapping("/{id}/declare")
    @Operation(summary = "申报登记（申报日期/回执号/经办人 → DONE）")
    public R<HrSocialChange> declare(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(socialChangeService.declare(id, payload));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "作废（可重开）")
    public R<HrSocialChange> voidChange(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(socialChangeService.voidChange(id, String.valueOf(payload.get("note"))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "单据详情")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(socialChangeService.detail(id));
    }
}

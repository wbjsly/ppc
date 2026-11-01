package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.MaskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 敏感字段登记位管理 API（add-perm-control-console，spec bi-query-governance 列级需求：
 * 登记位可管理）。复用 MaskRuleService 同表同审批链（RULE_TYPE=COL_SENSITIVE）。
 * 菜单 14.4.2。
 */
@Tag(name = "BI-敏感字段登记")
@RestController
@RequestMapping("/api/bi/sensitive-fields")
@RequiredArgsConstructor
public class BiSensitiveFieldController {

    private final MaskRuleService maskRuleService;

    @Operation(summary = "登记位列表（COL_SENSITIVE）")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> list() {
        return R.ok(maskRuleService.list("COL_SENSITIVE"));
    }

    @Operation(summary = "新增登记位（DRAFT，走脱敏审批链）")
    @PostMapping
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        payload.put("ruleType", "COL_SENSITIVE");
        // 敏感登记无掩码样式语义，固定 GENERIC 占位
        payload.putIfAbsent("maskStyle", "GENERIC");
        return R.ok(maskRuleService.create(payload));
    }

    @Operation(summary = "提交审批")
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(maskRuleService.submit(id));
    }

    @Operation(summary = "停用登记位")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(maskRuleService.disable(id));
    }

    @Operation(summary = "变更历史")
    @GetMapping("/{id}/logs")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> logs(@PathVariable String id) {
        return R.ok(maskRuleService.logs(id));
    }
}

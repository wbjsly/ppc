package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.MaskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BI 脱敏规则控制台（add-data-extract-console，spec bi-mask-rule-management）：
 * 类型化样式 CRUD + 数据治理专员单签审批 + 变更留痕。
 * 与 16.7.3 系统脱敏（/api/system/mask-rules）分域：本控制台管 erp_bi_perm_rule
 * 列级规则位（BI 查询层掩码执行由 QueryGovernance 承接）。
 */
@Tag(name = "BI 脱敏规则")
@RestController
@RequestMapping("/api/bi/mask-rules")
public class BiMaskRuleController {

    private final MaskRuleService service;

    public BiMaskRuleController(MaskRuleService service) {
        this.service = service;
    }

    @Operation(summary = "规则列表")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String ruleType) {
        return R.ok(service.list(ruleType));
    }

    @Operation(summary = "新建规则（DRAFT，审批后生效）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "修改规则（仅 DRAFT）")
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.update(id, payload));
    }

    @Operation(summary = "停用规则（即时生效）")
    @PostMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(service.disable(id));
    }

    @Operation(summary = "提交审批（ROLE_DATA_GOV 单签）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "变更历史")
    @GetMapping("/{id}/logs")
    public R<List<Map<String, Object>>> logs(@PathVariable String id) {
        return R.ok(service.logs(id));
    }
}

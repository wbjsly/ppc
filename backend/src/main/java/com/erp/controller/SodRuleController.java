package com.erp.controller;

import com.erp.common.R;
import com.erp.service.SoDCheckService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 职责分离预检与互斥规则（add-permission-approval-flow spec：合规性预检与 SoD 互斥，16.3.2）。
 * 限 ADMIN（SecurityConfig 细规则）；沙盘只读 + 规则 CRUD。
 */
@RestController
@RequestMapping("/api/system/sod-rules")
@RequiredArgsConstructor
public class SodRuleController {

    private final SoDCheckService soDCheckService;

    @Operation(summary = "互斥规则列表")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(soDCheckService.listRules());
    }

    @Operation(summary = "新增互斥规则（自动规范化 A<B；重复 422）")
    @PostMapping
    public R<Void> add(@RequestBody Map<String, String> body) {
        soDCheckService.addRule(body.get("roleA"), body.get("roleB"), body.get("remark"));
        return R.ok(null);
    }

    @Operation(summary = "删除互斥规则")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        soDCheckService.deleteRule(id);
        return R.ok(null);
    }

    @Operation(summary = "预检沙盘（超基线 / SoD 冲突 / 现有权限实时返回）")
    @PostMapping("/precheck")
    public R<Map<String, Object>> precheck(@RequestBody Map<String, Object> body) {
        Object raw = body.get("roleCodes");
        if (!(raw instanceof List)) {
            return R.fail(422, "roleCodes 须为数组");
        }
        List<String> codes = ((List<?>) raw).stream().map(String::valueOf).toList();
        Object uid = body.get("targetUserId");
        return R.ok(soDCheckService.check(uid == null ? null : String.valueOf(uid), codes));
    }
}

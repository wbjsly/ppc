package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmMaterialIssueService;
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

/**
 * 项目领料单（add-cost-collection-freeze，11.6.1 材料成本；BR-4.8-20 WBS 硬阻断）。
 * 写操作服务层校验角色（PM/PMO/ADMIN）与项目冻结态（design D6，去方法级粗放门控）。
 */
@Tag(name = "项目领料单")
@RestController
@RequestMapping("/api/pm/cost/material-issues")
@RequiredArgsConstructor
public class PmMaterialIssueController {

    private final PmMaterialIssueService service;

    @GetMapping
    @Operation(summary = "领料单列表")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String projectId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(projectId, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "领料单详情（含明细行）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @Operation(summary = "保存草稿（WBS 必填硬阻断；冻结项目 422）")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, false));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交（真实扣库存 MATERIAL_OUT；WBS 必填 L1 硬阻断）")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, true));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "审核（通过→材料成本归集+凭证；驳回必填原因）")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.review(id, payload));
    }
}

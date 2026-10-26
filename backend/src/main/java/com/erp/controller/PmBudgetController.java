package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmBudgetChangeService;
import com.erp.service.pm.PmBudgetService;
import com.erp.service.pm.PmRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 预算基线与变更（project-initiation，11.3.3）。 */
@Tag(name = "项目预算")
@RestController
@RequestMapping("/api/pm")
@RequiredArgsConstructor
public class PmBudgetController {

    private final PmBudgetService budgetService;
    private final PmBudgetChangeService changeService;
    private final PmRateService rateService;

    @GetMapping("/budgets")
    @Operation(summary = "预算行清单（含自动算人工/间接与合计）")
    public R<Map<String, Object>> budgetLines(@RequestParam String projectId) {
        return R.ok(budgetService.lines(projectId));
    }

    @PostMapping("/budgets/{projectId}/lines")
    @Operation(summary = "保存预算行（人工/间接服务端重算；锁定后 422）")
    public R<Map<String, Object>> saveLines(@PathVariable String projectId,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(budgetService.saveLines(projectId, payload));
    }

    @PostMapping("/budgets/{projectId}/submit")
    @Operation(summary = "提交基线审批（WBS 校验 + 合计>0 + DRAFT）")
    public R<Map<String, Object>> submitBaseline(@PathVariable String projectId) {
        return R.ok(budgetService.submitBaseline(projectId));
    }

    @GetMapping("/budgets/{projectId}/summary")
    @Operation(summary = "预算合计（六列汇总）")
    public R<Map<String, Object>> summary(@PathVariable String projectId) {
        return R.ok(budgetService.summary(projectId));
    }

    // ---------- 预算变更（BR-4.8-12，双签 PMO→FINANCE） ----------

    @GetMapping("/budget-changes")
    @Operation(summary = "变更单清单")
    public R<List<Map<String, Object>>> changeList(@RequestParam(required = false) String projectId) {
        return R.ok(changeService.list(projectId));
    }

    @PostMapping("/budget-changes/{projectId}")
    @Operation(summary = "提交变更申请（仅锁定项目；PENDING 单存在 422）")
    public R<Map<String, Object>> submitChange(@PathVariable String projectId,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(changeService.submit(projectId, payload));
    }

    // ---------- 岗位费率 ----------

    @GetMapping("/rates")
    @Operation(summary = "费率清单")
    public R<List<Map<String, Object>>> rateList() {
        return R.ok(rateService.list());
    }

    @PostMapping("/rates")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "保存费率（PMO/ADMIN）")
    public R<Map<String, Object>> saveRate(@RequestBody Map<String, Object> payload) {
        return R.ok(rateService.save(payload));
    }
}

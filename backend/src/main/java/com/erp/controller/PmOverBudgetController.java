package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmOverBudgetService;
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
 * 超支冻结与超预算审批（add-cost-collection-freeze，11.6.3；BR-4.8-21/22）。
 * 菜单权限=PMO/FINANCE/EXEC/ADMIN；解冻审批走统一底座 /api/qms/approvals/*。
 */
@Tag(name = "超支冻结与超预算审批")
@RestController
@RequestMapping("/api/pm/cost/over-budgets")
@RequiredArgsConstructor
public class PmOverBudgetController {

    private final PmOverBudgetService service;

    @GetMapping("/frozen")
    @Operation(summary = "冻结项目清单（含超支金额/比例）")
    public R<List<Map<String, Object>>> listFrozen() {
        return R.ok(service.listFrozen());
    }

    @GetMapping
    @Operation(summary = "超预算申请清单")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String projectId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(projectId, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "申请详情")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @Operation(summary = "提交超预算申请（BR-4.8-22 动态路由：≤5% PMO / ≤10% 财务 / >10% 分管领导）")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(service.submit(payload));
    }
}

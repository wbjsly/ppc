package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamMaintPlan;
import com.erp.service.eam.EamMaintPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 维护计划（add-asset-maintenance，spec asset-maintenance FR-4.13-3-1/2）。
 */
@Tag(name = "资产运维-维护计划")
@RestController
@RequestMapping("/api/eam/maint-plans")
public class EamMaintPlanController {

    private final EamMaintPlanService service;

    public EamMaintPlanController(EamMaintPlanService service) {
        this.service = service;
    }

    @Operation(summary = "分页（卡片号/关键标志）")
    @GetMapping
    public R<Page<EamMaintPlan>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String cardNo,
                                      @RequestParam(required = false) String criticalFlag) {
        return R.ok(service.page(current, size, cardNo, criticalFlag));
    }

    @Operation(summary = "创建/修改计划（关键设备置待审批）")
    @PostMapping
    public R<Map<String, Object>> save(@RequestBody EamMaintPlan plan) {
        return R.ok(service.savePlan(plan));
    }

    @Operation(summary = "关键设备计划提交审批（设备主管单签）")
    @PostMapping("/{id}/submit-approval")
    public R<Map<String, Object>> submitApproval(@PathVariable String id) {
        return R.ok(service.submitApproval(id));
    }

    @Operation(summary = "停用计划")
    @PostMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(service.disable(id));
    }

    @Operation(summary = "批量生成计划工单（幂等/停机顺延/备件预警）")
    @PostMapping("/generate-orders")
    public R<Map<String, Object>> generateOrders() {
        return R.ok(service.generatePlanOrders());
    }
}

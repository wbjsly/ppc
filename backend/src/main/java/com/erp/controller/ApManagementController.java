package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.ApPlan;
import com.erp.service.fin.ApAgingService;
import com.erp.service.fin.ApForecastService;
import com.erp.service.fin.ApPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 应付管理接口（add-ap-management：8.3.2 账龄 / 8.3.3 付款计划闭环 + 资金预测）。
 * 确认/核销/付款写逻辑复用既有 FinInvoiceController/FinPaymentController 端点。
 */
@Tag(name = "应付管理")
@RestController
@RequestMapping("/api/fin/ap-mgmt")
public class ApManagementController {

    private final ApAgingService agingService;
    private final ApPlanService planService;
    private final ApForecastService forecastService;

    public ApManagementController(ApAgingService agingService,
                                  ApPlanService planService,
                                  ApForecastService forecastService) {
        this.agingService = agingService;
        this.planService = planService;
        this.forecastService = forecastService;
    }

    // ================= 8.3.2 应付账龄 =================

    @Operation(summary = "应付账龄六段分析（复用 AR_AGING_BUCKETS，基数=POSTED 且未清>0）")
    @GetMapping("/aging")
    public R<Map<String, Object>> aging(@RequestParam(required = false) String supplierId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        return R.ok(agingService.aging(supplierId, baseDate));
    }

    @Operation(summary = "存量发票到期日批量回填（DUE_DATE IS NULL 且供应商账期为数字天数）")
    @PostMapping("/aging/backfill-due-date")
    public R<Map<String, Object>> backfillDueDate() {
        return R.ok(agingService.backfillDueDate());
    }

    // ================= 8.3.3 资金预测 =================

    @Operation(summary = "资金需求预测（滚动 7/30/90 天：未清应付+已排期+预付占用−银行余额→缺口）")
    @GetMapping("/forecast")
    public R<Map<String, Object>> forecast(@RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        return R.ok(forecastService.forecast(baseDate));
    }

    // ================= 8.3.3 付款计划闭环 =================

    @Operation(summary = "创建付款计划（PERIOD+法人唯一）")
    @PostMapping("/plans")
    public R<Map<String, Object>> createPlan(@RequestBody ApPlan req) {
        return R.ok(planService.create(req));
    }

    @Operation(summary = "提交计划审批")
    @PostMapping("/plans/{id}/submit")
    public R<Map<String, Object>> submitPlan(@PathVariable String id) {
        return R.ok(planService.submit(id));
    }

    @Operation(summary = "审批回调（通过/驳回留因）")
    @PostMapping("/plans/{id}/approval")
    public R<Void> planApproval(@PathVariable String id,
                                @RequestParam boolean approved,
                                @RequestParam(required = false) String reason) {
        planService.onApprovalResult(id, approved, reason);
        return R.ok();
    }

    @Operation(summary = "关闭计划（期末归档）")
    @PostMapping("/plans/{id}/close")
    public R<Map<String, Object>> closePlan(@PathVariable String id) {
        return R.ok(planService.close(id));
    }

    @Operation(summary = "计划分页")
    @GetMapping("/plans")
    public R<Page<ApPlan>> planPage(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String period,
                                    @RequestParam(required = false) String status) {
        return R.ok(planService.page(current, size, period, status));
    }

    @Operation(summary = "计划详情（分供应商行 JSON 解析）")
    @GetMapping("/plans/{id}")
    public R<Map<String, Object>> planDetail(@PathVariable String id) {
        return R.ok(planService.detail(id));
    }
}

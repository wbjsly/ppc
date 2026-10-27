package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmTrialConsumptionService;
import com.erp.service.pm.PmTrialCostService;
import com.erp.service.pm.PmTrialReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
 * 研发物料试制（add-rnd-material，M11.9）。
 * 11.9.1 领用管理（消耗确认+样品台账+退料）/ 11.9.2 试制成本分析。
 * 试制领料本体沿现有 /api/pm/cost/material-issues（加 TRIAL_FLAG 校验）；
 * 单据级角色校验在服务层（RD_LEAD），控制器不加 @PreAuthorize。
 */
@Tag(name = "研发物料试制")
@RestController
@RequestMapping("/api/pm/trial")
@RequiredArgsConstructor
public class PmTrialMaterialController {

    private final PmTrialConsumptionService consumptionService;
    private final PmTrialReturnService returnService;
    private final PmTrialCostService costService;

    // ==================== 消耗确认 + 样品台账（11.9.1） ====================

    @PostMapping("/consumptions")
    @Operation(summary = "试制消耗确认（防重 409；差异>10% L4；样品台账随确认落库）")
    public R<Map<String, Object>> confirmConsumption(@RequestBody Map<String, Object> payload) {
        return R.ok(consumptionService.confirm(payload));
    }

    @GetMapping("/consumptions")
    @Operation(summary = "消耗确认列表")
    public R<Map<String, Object>> listConsumptions(@RequestParam(required = false) String projectId,
                                                   @RequestParam(required = false) String issueId) {
        return R.ok(consumptionService.list(Map.of(
                "projectId", projectId == null ? "" : projectId,
                "issueId", issueId == null ? "" : issueId)));
    }

    @GetMapping("/consumptions/{id}")
    @Operation(summary = "消耗确认详情（含消耗行+样品）")
    public R<Map<String, Object>> consumptionDetail(@PathVariable String id) {
        return R.ok(consumptionService.detail(id));
    }

    @GetMapping("/samples")
    @Operation(summary = "样品台账（按批次/去向筛选，BR-4.8-39 追溯）")
    public R<Map<String, Object>> sampleLedger(@RequestParam(required = false) String issueId,
                                               @RequestParam(required = false) String batchNo,
                                               @RequestParam(required = false) String destination) {
        return R.ok(consumptionService.sampleLedger(Map.of(
                "issueId", issueId == null ? "" : issueId,
                "batchNo", batchNo == null ? "" : batchNo,
                "destination", destination == null ? "" : destination)));
    }

    // ==================== 结余退库（11.9.1） ====================

    @PostMapping("/returns")
    @Operation(summary = "保存草稿（质检结论/跨项目 L1/结余校验）")
    public R<Map<String, Object>> saveReturn(@RequestBody Map<String, Object> payload) {
        return R.ok(returnService.save(payload, false));
    }

    @PostMapping("/returns/submit")
    @Operation(summary = "提交退料单")
    public R<Map<String, Object>> submitReturn(@RequestBody Map<String, Object> payload) {
        return R.ok(returnService.save(payload, true));
    }

    @PutMapping("/returns/{id}/review")
    @Operation(summary = "审核（APPROVED 时 REUSABLE 入库+成本冲减，SCRAP 保留）")
    public R<Map<String, Object>> reviewReturn(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(returnService.review(id, payload));
    }

    @GetMapping("/returns")
    @Operation(summary = "退料单列表")
    public R<Map<String, Object>> listReturns(@RequestParam(required = false) String projectId,
                                              @RequestParam(required = false) String issueId) {
        return R.ok(returnService.list(Map.of(
                "projectId", projectId == null ? "" : projectId,
                "issueId", issueId == null ? "" : issueId)));
    }

    @GetMapping("/returns/{id}")
    @Operation(summary = "退料单详情（含退库行）")
    public R<Map<String, Object>> returnDetail(@PathVariable String id) {
        return R.ok(returnService.detail(id));
    }

    // ==================== 试制成本分析（11.9.2） ====================

    @GetMapping("/costs/summary")
    @Operation(summary = "试制成本汇总（TRIAL 任务聚合 + 预算比对 + 偏差率）")
    public R<Map<String, Object>> trialCostSummary(@RequestParam String projectId) {
        return R.ok(costService.summary(projectId));
    }
}

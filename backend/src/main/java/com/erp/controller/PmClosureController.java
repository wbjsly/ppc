package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmClosureService;
import com.erp.service.pm.PmClosureSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目结项归档（add-project-closure，M11.12，S-4.8-07）。
 * 11.12.1 结项检查（发起/检查单/遗留）+ 11.12.2 成本结算 + 撤回。
 */
@Tag(name = "项目结项归档")
@RestController
@RequestMapping("/api/pm/closure")
@RequiredArgsConstructor
public class PmClosureController {

    private final PmClosureService service;
    private final PmClosureSettlementService settlementService;
    private final com.erp.schedule.PmClosureDailyTask dailyTask;

    @PostMapping("/{projectId}/initiate")
    @Operation(summary = "发起结项（ACTIVE→CLOSING，生成检查单+六项证据）")
    public R<Map<String, Object>> initiate(@PathVariable String projectId) {
        return R.ok(service.initiate(projectId));
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "检查单详情（含行/遗留/结算状态）")
    public R<Map<String, Object>> detail(@PathVariable String projectId) {
        return R.ok(service.detail(projectId));
    }

    @PostMapping("/{projectId}/resubmit")
    @Operation(summary = "重新校验（DRAFT 态重算自动项，人工项保留）")
    public R<Map<String, Object>> resubmit(@PathVariable String projectId) {
        return R.ok(service.resubmit(projectId));
    }

    @PutMapping("/items/{itemId}")
    @Operation(summary = "人工项确认（PASS 必传附件）")
    public R<Map<String, Object>> confirmItem(@PathVariable String itemId,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(service.confirmItem(itemId, payload));
    }

    @PostMapping("/{projectId}/items")
    @Operation(summary = "新增自定义非强制检查项")
    public R<Map<String, Object>> addCustomItem(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.addCustomItem(projectId, payload));
    }

    @PutMapping("/items/{itemId}/remove")
    @Operation(summary = "删除自定义检查项")
    public R<Void> removeCustomItem(@PathVariable String itemId) {
        service.removeCustomItem(itemId);
        return R.ok(null);
    }

    @PostMapping("/{projectId}/submit")
    @Operation(summary = "提交审批（强制项全过+已结算+总结报告齐 → PMO）")
    public R<Map<String, Object>> submit(@PathVariable String projectId) {
        return R.ok(service.submitForApproval(projectId));
    }

    @PostMapping("/{projectId}/withdraw")
    @Operation(summary = "撤回结项（CLOSING→ACTIVE，审批单取消）")
    public R<Map<String, Object>> withdraw(@PathVariable String projectId) {
        return R.ok(service.withdraw(projectId));
    }

    @PutMapping("/{projectId}/remediations")
    @Operation(summary = "遗留项草稿批量保存（PMO 审批前，通过时转 OPEN）")
    public R<List<Map<String, Object>>> saveRemediations(@PathVariable String projectId,
                                                         @RequestBody List<Map<String, Object>> items) {
        return R.ok(service.saveRemediationDrafts(projectId, items));
    }

    @GetMapping("/{projectId}/remediations")
    @Operation(summary = "遗留工单列表")
    public R<List<Map<String, Object>>> remediations(@PathVariable String projectId) {
        return R.ok(service.remediations(projectId));
    }

    @GetMapping("/{projectId}/settlement/summary")
    @Operation(summary = "成本结算汇总（11.12.2）")
    public R<Map<String, Object>> settlementSummary(@PathVariable String projectId) {
        return R.ok(settlementService.summary(projectId));
    }

    @PostMapping("/{projectId}/settlement/confirm")
    @Operation(summary = "财务确认结算（解冻+不可变快照，FINANCE/FIN_MGR）")
    public R<Map<String, Object>> confirmSettlement(@PathVariable String projectId) {
        return R.ok(settlementService.confirm(projectId));
    }

    @PostMapping("/sweep")
    @Operation(summary = "手动触发遗留工单逾期升级扫描（ADMIN 运维，与 05:30 日报任务共用逻辑）")
    public R<Integer> sweep() {
        return R.ok(dailyTask.escalateOverdue());
    }
}

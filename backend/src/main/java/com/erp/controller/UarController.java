package com.erp.controller;

import com.erp.common.R;
import com.erp.service.UarService;
import io.swagger.v3.oas.annotations.Operation;
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
 * 权限复核台（add-access-review-console spec：16.6.1 季度任务 / 16.6.2 快照比对
 * / 16.6.3 回收执行 / 16.6.4 复核报告）。限 ADMIN（SecurityConfig 细规则）。
 */
@RestController
@RequestMapping("/api/system/uar")
@RequiredArgsConstructor
public class UarController {

    private final UarService uarService;

    @Operation(summary = "手动触发季度任务生成（风险分层；同周期幂等 422）")
    @PostMapping("/tasks/generate")
    public R<Map<String, Object>> generate() {
        return R.ok(uarService.generate());
    }

    @Operation(summary = "任务列表（含完成度统计）")
    @GetMapping("/tasks")
    public R<List<Map<String, Object>>> taskList() {
        return R.ok(uarService.taskList());
    }

    @Operation(summary = "任务项列表（mineOnly=仅本人待办）")
    @GetMapping("/items")
    public R<List<Map<String, Object>>> itemList(@RequestParam String taskId,
                                                 @RequestParam(required = false, defaultValue = "false") boolean mineOnly) {
        return R.ok(uarService.itemList(taskId, mineOnly));
    }

    @Operation(summary = "逐项判定（KEEP/RECLAIM；保留未使用/超基线须理由 ≥10 字）")
    @PostMapping("/items/{id}/judge")
    public R<Map<String, Object>> judge(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(uarService.judge(id, body.get("judgment"), body.get("reason")));
    }

    @Operation(summary = "执行收回（台账回收绕基线守卫 / 直删投影；幂等）")
    @PostMapping("/items/{id}/reclaim")
    public R<Map<String, Object>> executeReclaim(@PathVariable String id) {
        return R.ok(uarService.executeReclaim(id));
    }

    @Operation(summary = "汇总生成报告（task 唯一幂等）")
    @PostMapping("/tasks/{id}/summarize")
    public R<Map<String, Object>> summarize(@PathVariable String id) {
        return R.ok(uarService.summarize(id));
    }

    @Operation(summary = "ADMIN 确认（双签第一步）")
    @PostMapping("/tasks/{id}/confirm")
    public R<Void> confirm(@PathVariable String id) {
        uarService.confirmReport(id);
        return R.ok(null);
    }

    @Operation(summary = "内审会签归档（ROLE_UAR_AUDITOR，双签第二步）")
    @PostMapping("/tasks/{id}/sign")
    public R<Void> sign(@PathVariable String id) {
        uarService.signReport(id);
        return R.ok(null);
    }

    @Operation(summary = "报告列表")
    @GetMapping("/reports")
    public R<List<Map<String, Object>>> reportList() {
        return R.ok(uarService.reportList());
    }

    @Operation(summary = "逾期升级扫描（BR-5.6-10；定时任务默认关）")
    @PostMapping("/sweep-overdue")
    public R<Map<String, Object>> sweepOverdue() {
        return R.ok(uarService.sweepOverdue());
    }
}

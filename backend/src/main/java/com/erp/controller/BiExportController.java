package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.bi.BiExportTask;
import com.erp.security.IntfGuard;
import com.erp.service.bi.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 后台导出与审批（spec bi-query-governance C-4.10-05）。 */
@Tag(name = "BI 后台导出")
@RestController
@RequestMapping("/api/bi/export")
public class BiExportController {

    private final ExportService service;

    public BiExportController(ExportService service) {
        this.service = service;
    }

    @Operation(summary = "提交导出（>1 万行转超量审批）")
    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String dataset = body.get("dataset") == null ? "cost" : String.valueOf(body.get("dataset"));
        Object est = body.get("estRows");
        long estRows = est == null ? 0L : Long.parseLong(String.valueOf(est));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params") : Map.of();
        return R.ok(service.submit(dataset, params, estRows));
    }

    @Operation(summary = "超量审批（高危：仅 ADMIN）")
    @PostMapping("/{id}/approval")
    public R<Map<String, Object>> approval(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("超量导出审批");
        boolean ok = !Boolean.FALSE.equals(body.get("approved"));
        Object reason = body.get("reason");
        return R.ok(service.approve(id, ok, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "我的导出任务（完成即站内通知）")
    @GetMapping("/tasks")
    public R<List<BiExportTask>> tasks() {
        return R.ok(service.myTasks());
    }

    @Operation(summary = "待审批任务（高危列表，仅 ADMIN）")
    @GetMapping("/pending")
    public R<List<BiExportTask>> pending() {
        IntfGuard.requireAdmin("查看待审批导出");
        return R.ok(service.pendingApproval());
    }
}

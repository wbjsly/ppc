package com.erp.controller;

import com.erp.common.R;
import com.erp.service.BreakGlassService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 紧急权限台（add-break-glass-console spec：16.5.1 紧急申请 / 16.5.2 双人审批 / 16.5.3 事后审计）。
 * 限 ADMIN（SecurityConfig 细规则）；审批签署复用既有审批台端点。
 */
@RestController
@RequestMapping("/api/system/break-glass")
@RequiredArgsConstructor
public class BreakGlassController {

    private final BreakGlassService breakGlassService;

    // ---------- 故障工单 ----------

    @Operation(summary = "故障工单列表（status 过滤；申请侧仅 PROCESSING）")
    @GetMapping("/incidents")
    public R<List<Map<String, Object>>> incidentList(@RequestParam(required = false) String status) {
        return R.ok(breakGlassService.incidentList(status));
    }

    @Operation(summary = "新建故障工单（INC_NO 创建后不可改）")
    @PostMapping("/incidents")
    public R<Map<String, Object>> incidentSave(@RequestBody Map<String, Object> body) {
        return R.ok(breakGlassService.incidentSave(body));
    }

    @Operation(summary = "工单状态流转（PROCESSING → RESOLVED/CLOSED）")
    @PutMapping("/incidents/{id}/status")
    public R<Void> incidentTransition(@PathVariable String id, @RequestBody Map<String, String> body) {
        breakGlassService.incidentTransition(id, body.get("status"));
        return R.ok(null);
    }

    // ---------- 权限模板 ----------

    @Operation(summary = "模板列表（含停用）")
    @GetMapping("/templates")
    public R<List<Map<String, Object>>> templateList() {
        return R.ok(breakGlassService.templateList());
    }

    @Operation(summary = "新建模板（角色集合禁特权角色 422）")
    @PostMapping("/templates")
    public R<Map<String, Object>> templateSave(@RequestBody Map<String, Object> body) {
        return R.ok(breakGlassService.templateSave(body));
    }

    // ---------- 申请与审批 ----------

    @Operation(summary = "提交 Break-Glass 申请（工单处理中 + 模板启用 + 时长/说明/录屏校验）")
    @PostMapping("/requests")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return R.ok(breakGlassService.submit(
                str(body.get("targetUserId")),
                str(body.get("templateId")),
                str(body.get("incidentId")),
                body.get("durationHours") == null ? null
                        : Integer.parseInt(String.valueOf(body.get("durationHours"))),
                str(body.get("screenDeclare")),
                str(body.get("reason"))));
    }

    @Operation(summary = "申请单列表（status 过滤）")
    @GetMapping("/requests")
    public R<List<Map<String, Object>>> requestList(@RequestParam(required = false) String status) {
        return R.ok(breakGlassService.requestList(status));
    }

    @Operation(summary = "申请单详情（审批日志）")
    @GetMapping("/requests/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(breakGlassService.detail(id));
    }

    @Operation(summary = "提前归还（幂等，豁免基线守卫）")
    @PostMapping("/requests/{id}/reclaim")
    public R<Map<String, Object>> reclaim(@PathVariable String id) {
        return R.ok(breakGlassService.reclaim(id));
    }

    // ---------- 事后审计 ----------

    @Operation(summary = "事后审计报告列表（只读证据面）")
    @GetMapping("/audits")
    public R<List<Map<String, Object>>> auditList() {
        return R.ok(breakGlassService.auditList());
    }

    @Operation(summary = "签署审计结论（NO_VIOLATION/VIOLATION，不可留待定）")
    @PostMapping("/audits/{id}/sign")
    public R<Void> signAudit(@PathVariable String id, @RequestBody Map<String, String> body) {
        breakGlassService.signAudit(id, body.get("conclusion"));
        return R.ok(null);
    }

    @Operation(summary = "审计逾期告警扫描（24h 未签每 12h 提醒，定时任务默认关）")
    @PostMapping("/audits/sweep-overdue")
    public R<Map<String, Object>> sweepAuditOverdue() {
        return R.ok(breakGlassService.sweepAuditOverdue());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

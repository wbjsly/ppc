package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.service.eam.EamMaintainStatsService;
import com.erp.service.eam.EamWorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 统一工单（add-asset-maintenance，spec asset-maintenance FR-4.13-3-3~11、BR-16~20）。
 */
@Tag(name = "资产运维-工单")
@RestController
@RequestMapping("/api/eam/work-orders")
public class EamWorkOrderController {

    private final EamWorkOrderService service;
    private final EamMaintainStatsService statsService;

    public EamWorkOrderController(EamWorkOrderService service, EamMaintainStatsService statsService) {
        this.service = service;
        this.statsService = statsService;
    }

    @Operation(summary = "分页（类型/状态/卡片号）")
    @GetMapping
    public R<Page<EamWorkOrder>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String woType,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String cardNo) {
        return R.ok(service.page(current, size, woType, status, cardNo));
    }

    @Operation(summary = "详情（领料/工时/成本构成/子工单）")
    @GetMapping("/{woNo}")
    public R<Map<String, Object>> detail(@PathVariable String woNo) {
        return R.ok(service.detail(woNo));
    }

    @Operation(summary = "派工（BR-16 安全停机阻断）")
    @PostMapping("/{woNo}/assign")
    public R<Map<String, Object>> assign(@PathVariable String woNo, @RequestParam String assignee) {
        return R.ok(service.assign(woNo, assignee));
    }

    @Operation(summary = "开始执行")
    @PostMapping("/{woNo}/start")
    public R<Map<String, Object>> start(@PathVariable String woNo) {
        return R.ok(service.startExecution(woNo));
    }

    @Operation(summary = "执行记录（内容/问题/更换部件）")
    @PostMapping("/{woNo}/execution")
    public R<Map<String, Object>> execution(@PathVariable String woNo, @RequestBody Map<String, String> body) {
        return R.ok(service.recordExecution(woNo, body.get("execDesc"), body.get("issueDesc"),
                body.get("partsChanged")));
    }

    @Operation(summary = "创建子工单")
    @PostMapping("/{woNo}/sub-work-orders")
    public R<Map<String, Object>> subWorkOrder(@PathVariable String woNo, @RequestBody Map<String, String> body) {
        return R.ok(service.createSubWorkOrder(woNo, body.get("desc"), body.get("faultSymptom")));
    }

    @Operation(summary = "提交验收")
    @PostMapping("/{woNo}/submit-acceptance")
    public R<Map<String, Object>> submitAcceptance(@PathVariable String woNo) {
        return R.ok(service.submitForAcceptance(woNo));
    }

    @Operation(summary = "验收确认（who=OP/ENG，REPAIR 双确认）")
    @PostMapping("/{woNo}/accept")
    public R<Map<String, Object>> accept(@PathVariable String woNo, @RequestParam String who,
                                         @RequestParam(required = false) String resultEval) {
        return R.ok(service.accept(woNo, who, resultEval));
    }

    @Operation(summary = "关闭（BR-20 四前置 + 成本归集）")
    @PostMapping("/{woNo}/close")
    public R<Map<String, Object>> close(@PathVariable String woNo) {
        return R.ok(service.close(woNo));
    }

    @Operation(summary = "取消工单")
    @PostMapping("/{woNo}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable String woNo, @RequestParam String reason) {
        return R.ok(service.cancel(woNo, reason));
    }

    @Operation(summary = "备件领用（BR-17 超领校验 → MAINT 领料单）")
    @PostMapping("/{woNo}/spares")
    public R<Map<String, Object>> spares(@PathVariable String woNo,
                                         @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        String overReason = body.get("overReason") == null ? null : String.valueOf(body.get("overReason"));
        return R.ok(service.requestSpares(woNo, lines, overReason));
    }

    @Operation(summary = "回填工时（BR-20 前置；ENGR/TECH/OTHER）")
    @PostMapping("/{woNo}/times")
    public R<Map<String, Object>> recordTime(@PathVariable String woNo, @RequestBody Map<String, Object> body) {
        java.math.BigDecimal hours = body.get("workHours") == null ? null
                : new java.math.BigDecimal(String.valueOf(body.get("workHours")));
        return R.ok(service.recordTime(woNo,
                body.get("worker") == null ? null : String.valueOf(body.get("worker")),
                body.get("postType") == null ? null : String.valueOf(body.get("postType")),
                hours,
                body.get("workDate") == null ? null : String.valueOf(body.get("workDate"))));
    }

    @Operation(summary = "设备履历（工单/挂锁/工时/提示聚合）")
    @GetMapping("/maintenance-history/{cardId}")
    public R<Map<String, Object>> maintenanceHistory(@PathVariable String cardId) {
        return R.ok(statsService.maintenanceHistory(cardId));
    }

    @Operation(summary = "维修分析（MTBF/MTTR/费用趋势）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam String cardId,
                                        @RequestParam(defaultValue = "12") int months) {
        return R.ok(statsService.stats(cardId, months));
    }
}

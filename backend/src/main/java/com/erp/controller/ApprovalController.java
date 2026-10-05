package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通用审批底座接口（/api/qms/approvals）。
 * 待办按当前用户角色聚合；签署/驳回的节点角色不符由服务层 403（C-4.12-01 双签）。
 */
@Tag(name = "审批底座")
@RestController
@RequestMapping("/api/qms/approvals")
public class ApprovalController {

    private final ApprovalEngine engine;

    public ApprovalController(ApprovalEngine engine) {
        this.engine = engine;
    }

    /** 当前用户待办（可签节点 + 超时升级通知） */
    @Operation(summary = "审批待办", description = "按角色聚合可签节点与升级通知")
    @GetMapping("/todo")
    public R<List<Map<String, Object>>> todo() {
        return R.ok(engine.todo());
    }

    /** 节点通过 */
    @Operation(summary = "节点通过", description = "并行双签全过才推进；意见可空默认同意")
    @PostMapping("/pass")
    public R<Map<String, Object>> pass(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        String opinion = body.get("opinion") == null ? null : String.valueOf(body.get("opinion"));
        return R.ok(toMap(engine.pass(taskId, opinion)));
    }

    /** 节点驳回（意见 ≥2 字，整单驳回） */
    @Operation(summary = "节点驳回", description = "驳回意见必填 ≥2 字，实例置 REJECTED")
    @PostMapping("/reject")
    public R<Map<String, Object>> reject(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        String reason = str(body.get("reason"));
        if (reason != null && reason.trim().length() < 2) {
            throw new ServiceException(422, "驳回意见必填（至少 2 字）");
        }
        return R.ok(toMap(engine.reject(taskId, reason)));
    }

    /** 按业务单据的审批日志（实例 + 节点） */
    @Operation(summary = "审批日志")
    @GetMapping("/logs")
    public R<Map<String, Object>> logs(@RequestParam String bizType, @RequestParam String bizId) {
        return R.ok(engine.logs(bizType, bizId));
    }

    /** 按实例 ID 查询 */
    @Operation(summary = "审批实例详情")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(toMap(engine.getInstance(id)));
    }

    /** 手动触发超时扫描（默认由 schedule 驱动；测试与运维可手动触发，幂等） */
    @Operation(summary = "触发超时扫描", description = "72h 提醒 / 7 天升级，幂等")
    @PostMapping("/sweep")
    public R<Map<String, Object>> sweep() {
        int handled = engine.sweepTimeouts();
        return R.ok(Map.of("handled", handled));
    }

    private Map<String, Object> toMap(Object obj) {
        if (obj instanceof com.erp.entity.system.ApprovalInstance inst) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", inst.getId());
            m.put("apprNo", inst.getApprNo());
            m.put("bizType", inst.getBizType());
            m.put("bizId", inst.getBizId());
            m.put("title", inst.getTitle());
            m.put("status", inst.getStatus());
            m.put("escalateTo", inst.getEscalateTo());
            m.put("applyBy", inst.getApplyBy());
            m.put("applyDate", inst.getApplyDate());
            m.put("finishDate", inst.getFinishDate());
            m.put("verNo", inst.getVerNo());
            return m;
        }
        return Map.of();
    }

    private String str(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            throw new ServiceException(422, "参数必填");
        }
        return String.valueOf(o);
    }
}

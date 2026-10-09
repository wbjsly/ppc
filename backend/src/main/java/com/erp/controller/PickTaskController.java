package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.PickTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 拣货任务（4.7.1，spec picking-review）：队列/详情/改派/作废 + 状态机动作。
 * 查询认证即可，写口限 ADMIN/WAREHOUSE（SecurityConfig + 服务层双重强制）。
 */
@Tag(name = "拣货复核-拣货任务")
@RestController
@RequestMapping("/api/inv/pick-tasks")
public class PickTaskController {

    private final PickTaskService service;
    private final com.erp.service.inv.FreezePauseService freezePauseService;

    public PickTaskController(PickTaskService service,
                              com.erp.service.inv.FreezePauseService freezePauseService) {
        this.service = service;
        this.freezePauseService = freezePauseService;
    }

    @GetMapping
    @Operation(summary = "任务队列（状态/类型/关键词分页）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String srcType,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(status, srcType, keyword, current, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "任务详情（含行）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "改派拣货员（CREATED/PICKING，留痕前后人员）")
    public R<Map<String, Object>> assign(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        String picker = payload.get("picker") == null ? null
                : String.valueOf(payload.get("picker"));
        return R.ok(service.assign(id, picker));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "作废（仅 CREATED，原因必填）")
    public R<Map<String, Object>> cancel(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        String reason = payload.get("reason") == null ? null
                : String.valueOf(payload.get("reason"));
        return R.ok(service.cancel(id, reason));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "恢复冻结挂起（来源冻结单已 RELEASED 方可，WAREHOUSE/ADMIN）",
            description = "freeze-management ADDED 需求①：解冻不自动恢复，主管手动恢复回暂停前状态")
    public R<Map<String, Object>> resume(@PathVariable String id) {
        freezePauseService.resume("PICK_TASK", id);
        return R.ok(Map.of("id", id, "resumed", true));
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "状态迁移（开始拣货/送复核等，非法迁移 422）")
    public R<Map<String, Object>> transition(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        String from = payload.get("from") == null ? null : String.valueOf(payload.get("from"));
        String to = payload.get("to") == null ? null : String.valueOf(payload.get("to"));
        return R.ok(Map.of("task", service.transition(id, from, to)));
    }
}

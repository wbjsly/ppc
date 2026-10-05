package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.Capa;
import com.erp.entity.qms.CapaAction;
import com.erp.service.qms.CapaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CAPA/8D（6.6.1 8D 报告 / 6.6.2 措施执行 / 6.6.3 效果验证，spec capa-management）。
 * NCR 触发自动立项（BR-4.12-31）；8D D1~D8 顺序推进；Critical 24h 遏制倒计时。
 */
@Tag(name = "CAPA 8D")
@RestController
@RequestMapping("/api/qms/capa")
public class CapaController {

    private final CapaService service;

    public CapaController(CapaService service) {
        this.service = service;
    }

    @Operation(summary = "分页（状态/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "详情（8 步骤 + 措施 + 关联 NCR）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "措施列表")
    @GetMapping("/{id}/actions")
    public R<List<CapaAction>> actions(@PathVariable String id) {
        return R.ok(service.actions(id));
    }

    @Operation(summary = "手工立项（投诉/审核来源或 NCR 关联）")
    @PostMapping
    public R<Capa> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.createManual(body));
    }

    @Operation(summary = "8D 步骤推进（不可跳序；D2 5W2H / D4 根因证据 / D7 验证结论校验）")
    @PostMapping("/{id}/steps")
    public R<Capa> advance(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.advanceStep(id, body));
    }

    @Operation(summary = "录入纠正/预防措施（标准变更自动挂审批 BR-4.12-34）")
    @PostMapping("/{id}/actions")
    public R<CapaAction> addAction(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.addAction(id, body));
    }

    @Operation(summary = "措施完成（标准变更类须审批已通过）")
    @PostMapping("/actions/{actionId}/done")
    public R<CapaAction> completeAction(@PathVariable String actionId,
                                        @RequestBody Map<String, Object> body) {
        Object effect = body.get("effectDesc");
        return R.ok(service.completeAction(actionId, effect == null ? null : String.valueOf(effect)));
    }

    @Operation(summary = "遏制倒计时扫描（测试与运维手动触发）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        return R.ok(Map.of("containEscalated", service.sweepContainment()));
    }
}

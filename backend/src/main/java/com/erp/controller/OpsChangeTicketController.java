package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ops.ChangeTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 18.1 变更管理：申请/审批/启动/回滚/关闭 + 台账 + 结账保障开关。
 */
@Tag(name = "运维变更管理")
@RestController
@RequestMapping("/api/ops/change-tickets")
public class OpsChangeTicketController {

    private final ChangeTicketService service;

    public OpsChangeTicketController(ChangeTicketService service) {
        this.service = service;
    }

    @Operation(summary = "变更申请（结账期阻断 BR-5.8-14）")
    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return R.ok(service.submit(body));
    }

    @Operation(summary = "审批（预案三件套门禁 BR-5.8-01）")
    @PostMapping("/{id}/approve")
    public R<Map<String, Object>> approve(@PathVariable String id) {
        return R.ok(service.approve(id));
    }

    @Operation(summary = "启动执行（窗口剩余×1.5 门禁 BR-5.8-06）")
    @PostMapping("/{id}/start")
    public R<Map<String, Object>> start(@PathVariable String id) {
        return R.ok(service.start(id));
    }

    @Operation(summary = "回滚（记录回滚原因）")
    @PostMapping("/{id}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable String id,
                                           @RequestParam(required = false) String reason) {
        return R.ok(service.rollback(id, reason));
    }

    @Operation(summary = "关闭（已回滚则原因必填 BR-5.8-04）")
    @PostMapping("/{id}/close")
    public R<Map<String, Object>> close(@PathVariable String id,
                                        @RequestParam(required = false) String rollbackReason) {
        return R.ok(service.close(id, rollbackReason));
    }

    @Operation(summary = "变更单台账（18.1.2 日历/18.1.5 回滚原因）")
    @GetMapping
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.list(status, page, size));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.ProcApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 请购审批（2.1.4，/api/proc/approvals）：提交/通过/驳回/待办/日志。 */
@RestController
@RequestMapping("/api/proc/approvals")
public class ProcApprovalController {

    private final ProcApprovalService service;

    public ProcApprovalController(ProcApprovalService service) {
        this.service = service;
    }

    /** 提交审批（聚合可提交态，三档限额路由） */
    @PostMapping("/submit")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return R.ok(service.submit(str(body.get("prId"))));
    }

    /** 节点通过（防重：非 ACTIVE 422） */
    @PostMapping("/pass")
    public R<List<Map<String, Object>>> pass(@RequestBody Map<String, Object> body) {
        return R.ok(service.pass(str(body.get("taskId"))));
    }

    /** 节点驳回（原因≥2字） */
    @PostMapping("/reject")
    public R<List<Map<String, Object>>> reject(@RequestBody Map<String, Object> body) {
        return R.ok(service.reject(str(body.get("taskId")), str(body.get("reason"))));
    }

    /** 待办列表（含 sweep 标记、路由链、辅助信息） */
    @GetMapping("/todo")
    public R<List<Map<String, Object>>> todo() {
        return R.ok(service.todo());
    }

    /** 按 PR 审批日志 */
    @GetMapping("/pr/{prId}/logs")
    public R<List<Map<String, Object>>> logs(@PathVariable String prId) {
        return R.ok(service.logs(prId));
    }

    private String str(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            throw new ServiceException(422, "参数必填");
        }
        return String.valueOf(o);
    }
}

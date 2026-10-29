package com.erp.controller;

import com.erp.common.R;
import com.erp.service.AuthzIncidentService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 越权检测控制台（16.8，spec authz-detection）：告警/降权列表、处置三动作、
 * 手动恢复与到期扫描。全部限 ADMIN（SecurityConfig 细规则）。
 */
@RestController
@RequestMapping("/api/system/authz-incidents")
@RequiredArgsConstructor
public class AuthzIncidentController {

    private final AuthzIncidentService authzIncidentService;

    @Operation(summary = "告警/降权列表（status 过滤：ACTIVE/RESTORED/DISABLED，分页倒序）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return R.ok(authzIncidentService.list(status, page, size));
    }

    @Operation(summary = "处置三动作（action=MISUSE/RESTORE/MALICIOUS；conclusion ≥10 字）")
    @PostMapping("/{id}/dispose")
    public R<Void> dispose(@PathVariable String id, @RequestBody Map<String, Object> body) {
        authzIncidentService.dispose(id,
                body.get("action") == null ? null : String.valueOf(body.get("action")),
                body.get("conclusion") == null ? null : String.valueOf(body.get("conclusion")),
                body.get("trainingFlag") == null ? null : String.valueOf(body.get("trainingFlag")));
        return R.ok(null);
    }

    @Operation(summary = "手动提前恢复（幂等）")
    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null
                ? null : String.valueOf(body.get("reason"));
        authzIncidentService.restoreNow(id, reason);
        return R.ok(null);
    }

    @Operation(summary = "到期恢复扫描（7 天届满 → RESTORED，幂等）")
    @PostMapping("/sweep-restore")
    public R<Map<String, Object>> sweepRestore() {
        return R.ok(authzIncidentService.restoreExpired());
    }
}

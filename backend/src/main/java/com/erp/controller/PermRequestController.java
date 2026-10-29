package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ActivationSweepService;
import com.erp.service.PermRequestService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 权限申请台（add-permission-approval-flow spec：16.3.1 权限申请）。
 * 限 ADMIN（SecurityConfig 细规则）；审批执行复用既有审批台。
 */
@RestController
@RequestMapping("/api/system/perm-requests")
@RequiredArgsConstructor
public class PermRequestController {

    private final PermRequestService permRequestService;
    private final ActivationSweepService activationSweepService;

    @Operation(summary = "申请单列表（status/keyword/outWindow 过滤）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String outWindow) {
        return R.ok(permRequestService.list(status, keyword, outWindow));
    }

    @Operation(summary = "提交常规权限申请（四字段校验 + 风险路由 + 强制预检快照）")
    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return R.ok(permRequestService.submitNormal(
                str(body.get("targetUserId")),
                strings(body.get("roleCodes")),
                str(body.get("dataScope")),
                str(body.get("reason")),
                str(body.get("ticketNo")),
                body.get("validMonths") == null ? null : Integer.parseInt(String.valueOf(body.get("validMonths"))),
                str(body.get("activationMode")),
                body.get("activateAt") == null || String.valueOf(body.get("activateAt")).isBlank()
                        ? null : LocalDateTime.parse(String.valueOf(body.get("activateAt")))));
    }

    @Operation(summary = "提交超基线例外申请（KIND=EXCEPTION，16.2 调整改道入口）")
    @PostMapping("/exception")
    public R<Map<String, Object>> submitException(@RequestBody Map<String, Object> body) {
        return R.ok(permRequestService.submitException(
                str(body.get("targetUserId")),
                strings(body.get("targetRoles")),
                strings(body.get("beyondRoles")),
                str(body.get("reason"))));
    }

    @Operation(summary = "申请单详情（预检快照 + 审批日志）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(permRequestService.detail(id));
    }

    @Operation(summary = "窗口外变更复核台账（16.4.3 只读；限 ADMIN）")
    @GetMapping("/out-window-ledger")
    public R<List<Map<String, Object>>> outWindowLedger() {
        return R.ok(permRequestService.outWindowLedger());
    }

    @Operation(summary = "指定时点生效手动扫描（@Scheduled enabled 默认 false，design D5）")
    @PostMapping("/sweep-activation")
    public R<Map<String, Object>> sweepActivation() {
        return R.ok(activationSweepService.sweepDue(LocalDateTime.now()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        if (!(v instanceof List)) {
            return null;
        }
        return ((List<?>) v).stream().map(String::valueOf).toList();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.GrantLedgerService;
import com.erp.service.GrantSweepService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 授予台账台（add-permission-approval-flow spec：16.3.3 到期回收）。
 * 限 ADMIN（SecurityConfig 细规则）；回收幂等，sweep 手动触发（定时任务默认关）。
 */
@RestController
@RequestMapping("/api/system/grant-ledger")
@RequiredArgsConstructor
public class GrantLedgerController {

    private final GrantLedgerService grantLedgerService;
    private final GrantSweepService grantSweepService;

    @Operation(summary = "台账列表（status：ACTIVE/RECLAIMED/EXPIRED，空=全部）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return R.ok(grantLedgerService.list(status));
    }

    @Operation(summary = "手动回收（幂等；回收前自动留权限快照）")
    @PostMapping("/{id}/reclaim")
    public R<Map<String, Object>> reclaim(@PathVariable String id) {
        return R.ok(grantLedgerService.reclaim(id, operator()));
    }

    @Operation(summary = "到期回收+7 日提醒手动扫描（@Scheduled enabled 默认 false）")
    @PostMapping("/sweep")
    public R<Map<String, Object>> sweep() {
        return R.ok(grantSweepService.sweep(LocalDateTime.now()));
    }

    private static String operator() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}

package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.RedInvoice;
import com.erp.service.fin.RedFlushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 红字冲销接口（M9-4-1，spec red-invoice-void）。
 * 权限对齐 M9-4 菜单三角色（ROLE_FINANCE/FIN_MGR/ADMIN）；
 * 审批签署走 /api/qms/approvals（ApprovalEngine 底座）。
 */
@RestController
@RequestMapping("/api/fin/red-flush-applies")
@RequiredArgsConstructor
@Tag(name = "红字冲销（M9-4-1）")
public class RedFlushController {

    private final RedFlushService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "红冲申请创建（蓝票校验 C-4.14-12 + 金额校验 C-4.14-02 + 跨期打标）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "提交审批（金额分级 + 频繁红冲升级 FR-4.14-4-2）")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申请单分页（状态/客户/关键字筛选）")
    public R<Page<RedFlushApply>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String customerId,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, customerId, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "详情（申请 + 审批轨迹 + 信息单 + 红票 + 凭证）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PutMapping("/{id}/info-sheet-no")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "信息表编号登记（开红票门禁 BR-4.14-25）")
    public R<RedFlushApply> recordInfoSheetNo(@PathVariable String id,
                                              @RequestBody Map<String, String> body) {
        return R.ok(service.recordInfoSheetNo(id, body.get("infoSheetNo")));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "开红票（台账/AR/凭证联动，FR-4.14-4-4/5/6）")
    public R<RedInvoice> issue(@PathVariable String id) {
        return R.ok(service.issue(id));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "红冲后重开（BR-4.14-29：原开票申请复活 + 强制关联红冲单号）")
    public R<Map<String, Object>> reopen(@PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        return R.ok(service.reopen(id, body.get("reopenNote")));
    }
}

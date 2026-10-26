package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.BankReconciliationService;
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
 * 银行对账（M8-7，spec bank-reconciliation）：余额级对账闭环。
 * 8.7.1 流水导入/8.7.2 自动比对复用 /api/fin/bank-receipts（同一数据源）。
 */
@Tag(name = "银行对账")
@RestController
@RequestMapping("/api/fin/bank-reconciliation")
public class BankReconciliationController {

    private final BankReconciliationService service;

    public BankReconciliationController(BankReconciliationService service) {
        this.service = service;
    }

    @Operation(summary = "录入/修改对账单（DRAFT/CONFIRMED 可改）")
    @PostMapping("statements")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload));
    }

    @Operation(summary = "对账单列表（可按期间过滤）")
    @GetMapping("statements")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String period) {
        return R.ok(service.list(period));
    }

    @Operation(summary = "对账单详情（差异率/调节表/未达账项）")
    @GetMapping("statements/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "出纳确认（DRAFT→CONFIRMED）")
    @PostMapping("statements/{id}/confirm")
    public R<Void> confirm(@PathVariable String id) {
        service.confirm(id);
        return R.ok(null);
    }

    @Operation(summary = "财务主管审核（CONFIRMED→AUDITED，BR-4.6-36）")
    @PostMapping("statements/{id}/audit")
    public R<Void> audit(@PathVariable String id) {
        service.audit(id);
        return R.ok(null);
    }

    @Operation(summary = "撤销（回 DRAFT，AUDITED 撤销仅主管且必填原因留痕）")
    @PostMapping("statements/{id}/revoke")
    public R<Void> revoke(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        service.revoke(id, reason == null || "null".equals(reason) ? null : reason);
        return R.ok(null);
    }

    @Operation(summary = "四类未达账项实时分类（含账龄红标）")
    @GetMapping("outstanding-items")
    public R<Map<String, Object>> outstandingItems(@RequestParam String bankAccountId,
                                                   @RequestParam String period) {
        return R.ok(service.outstandingItems(bankAccountId, period));
    }

    @Operation(summary = "差异处理留痕（追加式：PROCESSING/DONE/ESCALATED）")
    @PostMapping("handling")
    public R<Map<String, Object>> markHandling(@RequestBody Map<String, Object> payload) {
        return R.ok(service.markHandling(payload));
    }

    @Operation(summary = "处理记录回看（按账户+期间）")
    @GetMapping("handling-records")
    public R<List<Map<String, Object>>> handlingRecords(@RequestParam String bankAccountId,
                                                        @RequestParam String period) {
        return R.ok(service.handlingRecords(bankAccountId, period));
    }
}

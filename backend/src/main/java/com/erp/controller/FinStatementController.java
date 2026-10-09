package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinSupplierStatement;
import com.erp.service.fin.SupplierStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 供应商对账单与付款冻结（2.7.3，spec supplier-statement-reconciliation，C-4.2-15）。
 * 查询 ADMIN/PM；登记与双签发起 ADMIN/PM（双签节点角色由审批底座校验）。
 */
@Tag(name = "供应商对账单")
@RestController
@RequestMapping("/api/fin/statements")
public class FinStatementController {

    private final SupplierStatementService service;

    public FinStatementController(SupplierStatementService service) {
        this.service = service;
    }

    @Operation(summary = "分页（供应商/状态/对账日期区间）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(service.page(current, size, supplierId, status, dateFrom, dateTo));
    }

    @Operation(summary = "系统匹配（S-4.9-06：ERP 明细与对账单逐笔匹配、差异标红）")
    @PostMapping("/{id}/match")
    public R<Map<String, Object>> match(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.match(id, payload));
    }

    @Operation(summary = "详情（比对基准/差异/双签记录）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "登记对账单（自动与 OPEN 暂估余额比对，超容差冻结付款）")
    @PostMapping
    public R<FinSupplierStatement> register(@RequestBody Map<String, Object> payload) {
        return R.ok(service.register(payload));
    }

    @Operation(summary = "发起双签确认（PM → 财务；通过解冻，驳回维持冻结）")
    @PostMapping("/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }
}

package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinAccrual;
import com.erp.service.fin.AccrualService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 应付暂估（2.7.1，spec ap-accrual）。
 * 查询放行 ADMIN/PM；手工冲回与迁移批次财务确认仅 ADMIN（SecurityConfig 细规则 + 服务层双校验）。
 */
@Tag(name = "应付暂估")
@RestController
@RequestMapping("/api/fin/accruals")
public class FinAccrualController {

    private final AccrualService service;

    public FinAccrualController(AccrualService service) {
        this.service = service;
    }

    @Operation(summary = "台账分页（供应商/PO/状态/生成日期区间）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String poNo,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(service.page(current, size, supplierId, poNo, status, dateFrom, dateTo));
    }

    @Operation(summary = "汇总卡（暂估余额 / 本月生成 / 本月冲回）")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(service.summary());
    }

    @Operation(summary = "明细（GR/PO 关联链 + 暂估凭证）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "手工冲回（仅 ADMIN：关联发票号 + 原因必填，OPEN→REVERSED 留痕）")
    @PostMapping("/{accrualNo}/reverse")
    public R<FinAccrual> reverse(@PathVariable String accrualNo,
                                 @RequestBody Map<String, Object> body) {
        Object invoiceNo = body.get("invoiceNo");
        Object reason = body.get("reason");
        return R.ok(service.reverseManual(accrualNo,
                invoiceNo == null ? null : String.valueOf(invoiceNo),
                reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "合并迁移批次财务确认（仅 ADMIN，确认后自动重跑三方匹配 BR-4.1-29）")
    @PostMapping("/migrations/{batchNo}/confirm")
    public R<Map<String, Object>> confirmMigration(@PathVariable String batchNo) {
        int n = service.confirmMigration(batchNo);
        return R.ok(Map.of("batchNo", batchNo, "confirmed", n));
    }
}

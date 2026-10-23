package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.BankReceipt;
import com.erp.service.fin.BankReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 回单对账（M8-4-3，spec bank-receipt-reconciliation）。
 */
@Tag(name = "回单对账")
@RestController
@RequestMapping("/api/fin/bank-receipts")
public class BankReceiptController {

    private final BankReceiptService service;

    public BankReceiptController(BankReceiptService service) {
        this.service = service;
    }

    @Operation(summary = "分页（匹配状态/关键字/日期区间）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String matchStatus,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(service.page(current, size, matchStatus, keyword, dateFrom, dateTo));
    }

    @Operation(summary = "详情（含匹配单据）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "手工录入回单")
    @PostMapping
    public R<BankReceipt> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "CSV 批量导入（逐行报告：重复跳过/非法行原因）")
    @PostMapping("/import")
    public R<Map<String, Object>> importCsv(@RequestBody Map<String, String> body) {
        return R.ok(service.importCsv(body.get("csv")));
    }

    @Operation(summary = "自动匹配（账户+方向+金额±3 天，一对一）")
    @PostMapping("/auto-match")
    public R<Map<String, Object>> autoMatch() {
        return R.ok(service.autoMatch());
    }

    @Operation(summary = "人工匹配（方向/账户校验）")
    @PostMapping("/{id}/match")
    public R<BankReceipt> manualMatch(@PathVariable String id,
                                      @RequestBody Map<String, String> body) {
        return R.ok(service.manualMatch(id, body.get("docId")));
    }

    @Operation(summary = "标记异常")
    @PostMapping("/{id}/exception")
    public R<BankReceipt> markException(@PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        return R.ok(service.markException(id, body.get("reason")));
    }

    @Operation(summary = "解除匹配（回单回 UNMATCHED、单据回 POSTED，留痕）")
    @PostMapping("/{id}/unreconcile")
    public R<BankReceipt> unreconcile(@PathVariable String id,
                                      @RequestBody Map<String, String> body) {
        return R.ok(service.unreconcile(id, body.get("reason")));
    }
}

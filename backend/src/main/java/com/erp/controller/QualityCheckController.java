package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.QualityCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据质量校验控制台（add-data-extract-console，spec bi-quality-check）：
 * 规则配置化 + 立即校验 + 批次复验解除阻断 + 校验报告。
 */
@Tag(name = "BI 质量校验")
@RestController
@RequestMapping("/api/bi/quality")
public class QualityCheckController {

    private final QualityCheckService service;

    public QualityCheckController(QualityCheckService service) {
        this.service = service;
    }

    @Operation(summary = "质量规则列表")
    @GetMapping("/rules")
    public R<List<Map<String, Object>>> rules(@RequestParam(required = false) String status) {
        return R.ok(service.listRules(status));
    }

    @Operation(summary = "新建质量规则")
    @PostMapping("/rules")
    public R<Map<String, Object>> createRule(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createRule(payload));
    }

    @Operation(summary = "修改质量规则")
    @PutMapping("/rules/{id}")
    public R<Map<String, Object>> updateRule(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateRule(id, payload));
    }

    @Operation(summary = "删除质量规则（有报告改停用）")
    @DeleteMapping("/rules/{id}")
    public R<Void> deleteRule(@PathVariable String id) {
        service.deleteRule(id);
        return R.ok(null);
    }

    @Operation(summary = "立即校验（单规则手动执行）")
    @PostMapping("/rules/{id}/run-now")
    public R<Map<String, Object>> runNow(@PathVariable String id) {
        return R.ok(service.runNow(id));
    }

    @Operation(summary = "批次复验解除阻断（无 SEVERE FAIL 自动置就绪）")
    @PostMapping("/recheck/{batchNo}")
    public R<Map<String, Object>> recheck(@PathVariable String batchNo) {
        return R.ok(service.recheckBlockedBatch(batchNo));
    }

    @Operation(summary = "校验报告查询")
    @GetMapping("/results")
    public R<List<Map<String, Object>>> results(@RequestParam(required = false) String batchNo,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to) {
        return R.ok(service.listResults(batchNo, from, to));
    }
}

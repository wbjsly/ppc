package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.CopqEntry;
import com.erp.service.qms.CopqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 质量成本 COPQ（6.7.1 成本归集 / 6.7.2 损失分析，spec quality-cost）。
 * 四类归集 + 异常 ≥10× 月均阻断（BR-4.12-39）+ 财务确认（BR-4.12-40）+ 报表红标（BR-4.12-41）。
 */
@Tag(name = "质量成本 COPQ")
@RestController
@RequestMapping("/api/qms/copq")
public class CopqController {

    private final CopqService service;

    public CopqController(CopqService service) {
        this.service = service;
    }

    @Operation(summary = "分页（类别/状态/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, category, status));
    }

    @Operation(summary = "手工录入归集")
    @PostMapping
    public R<CopqEntry> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.createManual(body));
    }

    @Operation(summary = "提交财务确认（底座 CopqFinance，ADMIN 代）")
    @PostMapping("/{id}/finance")
    public R<CopqEntry> submitFinance(@PathVariable String id) {
        return R.ok(service.submitFinance(id));
    }

    @Operation(summary = "异常核实（通过回草稿可确认 / 不予入账作废）")
    @PostMapping("/{id}/anomaly")
    public R<CopqEntry> resolveAnomaly(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        Object remark = body.get("remark");
        return R.ok(service.resolveAnomaly(id, pass, remark == null ? "" : String.valueOf(remark)));
    }

    @Operation(summary = "报表（正式/草稿分列 + 外部失败占比红标 + 供应商维度）")
    @GetMapping("/report")
    public R<Map<String, Object>> report(@RequestParam(required = false) String month) {
        return R.ok(service.report(month));
    }

    @Operation(summary = "按来源反查（NCR/RETURN/SCAR/CAPA）")
    @GetMapping("/by-source")
    public R<List<CopqEntry>> bySource(@RequestParam String sourceType, @RequestParam String sourceId) {
        return R.ok(service.bySource(sourceType, sourceId));
    }
}

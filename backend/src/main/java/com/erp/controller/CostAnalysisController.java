package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.CostAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 2.9.1 成本分析（spec procurement-cost-analysis 三 Tab + 立即扫描）。 */
@Tag(name = "采购成本分析")
@RestController
@RequestMapping("/api/bi/cost")
public class CostAnalysisController {

    private final CostAnalysisService service;

    public CostAnalysisController(CostAnalysisService service) {
        this.service = service;
    }

    @Operation(summary = "Tab1 成本构成（双口径五维，双口径可切换展示）")
    @GetMapping("/composition")
    public R<Map<String, Object>> composition(@RequestParam(required = false) String monthTag,
                                              @RequestParam(required = false) String itemCode,
                                              @RequestParam(required = false) String supplierId,
                                              @RequestParam(required = false) String categoryCode,
                                              @RequestParam(required = false) String buyer,
                                              @RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("monthTag", monthTag);
        p.put("itemCode", itemCode);
        p.put("supplierId", supplierId);
        p.put("categoryCode", categoryCode);
        p.put("buyer", buyer);
        p.put("current", current);
        p.put("size", size);
        return R.ok(service.composition(p));
    }

    @Operation(summary = "价差明细下钻（剔除暂估行并给剔除计数）")
    @GetMapping("/price-diff")
    public R<Map<String, Object>> priceDiff(@RequestParam(required = false) String monthTag,
                                            @RequestParam(required = false) String itemCode,
                                            @RequestParam(required = false) String supplierId) {
        return R.ok(service.priceDiff(monthTag, itemCode, supplierId));
    }

    @Operation(summary = "价格趋势（月度均价+环比同比+MA3+协议价参考线，basis=PO/INVOICE）")
    @GetMapping("/trend")
    public R<Map<String, Object>> trend(@RequestParam String itemCode,
                                        @RequestParam(defaultValue = "PO") String basis) {
        return R.ok(service.trend(itemCode, basis));
    }

    @Operation(summary = "降本三维汇总（环比/同比自动基准，只读月快照）")
    @GetMapping("/savings")
    public R<Map<String, Object>> savings(@RequestParam(required = false) String monthTag,
                                          @RequestParam(defaultValue = "SUPPLIER") String dimension) {
        return R.ok(service.savings(monthTag, dimension));
    }

    @Operation(summary = "立即扫描（实时补算异动，不覆盖已处置）")
    @PostMapping("/scan")
    public R<Map<String, Object>> scan() {
        return R.ok(service.scanNow());
    }
}

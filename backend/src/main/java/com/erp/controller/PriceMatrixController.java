package com.erp.controller;

import com.erp.common.R;
import com.erp.service.proc.PriceMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 比价矩阵工作台（2.2.4，change add-price-comparison-matrix）。
 * 全部 GET 读端点——SecurityConfig 写规则（POST/PUT/DELETE /api/proc/** → ADMIN）不涉及。
 * 定标与报价动作仍走 {@link RfqController}（写路径不变）。
 */
@RestController
@RequestMapping("/api/proc/price-matrix")
@RequiredArgsConstructor
public class PriceMatrixController {

    private final PriceMatrixService service;

    @Operation(summary = "可比价品类选项", description = "有 RFQ 行的物料所属品类（工作台选择器）")
    @GetMapping("/categories")
    public R<List<Map<String, Object>>> categories() {
        return R.ok(service.categories());
    }

    @Operation(summary = "品类视角", description = "按物料品类聚合多供应商跨 RFQ 比对（design D3）")
    @GetMapping("/category")
    public R<Map<String, Object>> categoryView(@RequestParam(required = false) String categoryCode) {
        // required=false：参数缺失由服务层统一 422（Spring 缺参会映射为 500）
        return R.ok(service.categoryView(categoryCode));
    }

    @Operation(summary = "供应商视角", description = "供应商在同品类各 RFQ 的报价明细与汇总（design D3）")
    @GetMapping("/supplier")
    public R<Map<String, Object>> supplierView(@RequestParam(required = false) String categoryCode,
                                               @RequestParam(required = false) String supplierId) {
        return R.ok(service.supplierView(categoryCode, supplierId));
    }

    @Operation(summary = "比价记录列表", description = "快照冗余列（BR-4.2-14 归档）")
    @GetMapping("/snapshots")
    public R<List<Map<String, Object>>> snapshots() {
        return R.ok(service.snapshots());
    }

    @Operation(summary = "快照回看", description = "按 RFQ 解析快照 JSON；未定标 404")
    @GetMapping("/snapshots/{rfqId}")
    public R<Map<String, Object>> snapshot(@PathVariable String rfqId) {
        return R.ok(service.snapshot(rfqId));
    }
}

package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.vmi.VmiStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 寄售库存台账（2.8.1，spec vmi-consignment R4/BR-4.2-01）。
 * 读放行 ADMIN/PM/WAREHOUSE（SecurityConfig /api/proc/vmi/** GET 规则）。
 */
@Tag(name = "寄售库存台账")
@RestController
@RequestMapping("/api/proc/vmi/stocks")
public class VmiStockController {

    private final VmiStockService service;

    public VmiStockController(VmiStockService service) {
        this.service = service;
    }

    @Operation(summary = "台账分页（供应商/物料筛选；加载即触发补货与账龄处置惰性扫描）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String itemCode) {
        return R.ok(service.page(current, size, supplierId, itemCode));
    }
}

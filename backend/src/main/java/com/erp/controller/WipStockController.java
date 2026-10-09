package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.StockSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 在制库存查询（spec stock-snapshot，4.3.3；FR-4.4-2-4 数据源待接入）。
 * 工单域（5.4）未建 → 恒空列表 + 页面空态（偏差 D5）；接口契约就位，
 * 5.x 落地时仅替换数据源，页面与本接口不变。
 */
@Tag(name = "库存快照")
@RestController
@RequestMapping("/api/inv/wip-stock")
public class WipStockController {

    private final StockSnapshotService service;

    public WipStockController(StockSnapshotService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "在制库存查询（当前恒空，待工单域接入）")
    public R<Map<String, Object>> query() {
        return R.ok(service.wipQuery());
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.StockSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 库存快照查询（spec stock-snapshot，4.3.1 可用库存）。
 * 只读接口：仅需认证（读接口口径），菜单 PERM 管可见性。
 */
@Tag(name = "库存快照")
@RestController
@RequestMapping("/api/inv/stock-snapshot")
public class StockSnapshotController {

    private final StockSnapshotService service;

    public StockSnapshotController(StockSnapshotService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "可用库存查询（四列口径）",
            description = "在手−冻结−预留实时计算可用；含合计、asOf、待核实标记（FR-4.4-2-2 / BR-4.4-14）")
    public R<Map<String, Object>> query(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.query(warehouseCode, itemCode, batchNo, keyword));
    }

    @GetMapping("/reserved-detail")
    @Operation(summary = "预留下钻（SO 单号/行号/锁定时间/数量）")
    public R<List<Map<String, Object>>> reservedDetail(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo) {
        return R.ok(service.reservedDetail(warehouseCode, itemCode, batchNo));
    }

    @GetMapping("/freeze-detail")
    @Operation(summary = "冻结下钻（台账逐条：类型/原因/状态/来源）")
    public R<List<Map<String, Object>>> freezeDetail(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo) {
        return R.ok(service.freezeDetail(warehouseCode, itemCode, batchNo));
    }

    @GetMapping("/check-report")
    @Operation(summary = "最近一次恒等式校验报告（待核实标记来源，BR-4.4-15）")
    public R<Map<String, Object>> latestCheckReport() {
        return R.ok(service.latestCheckReport());
    }
}

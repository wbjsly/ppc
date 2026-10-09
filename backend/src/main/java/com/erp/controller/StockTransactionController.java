package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvTransaction;
import com.erp.service.inv.StockTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 出入库流水查询（spec stock-posting-engine；仅需认证）。
 * 供作业台详情抽屉与后续批次追溯（4.13，偏差 D4）消费。
 */
@Tag(name = "出入库流水")
@RestController
@RequestMapping("/api/inv/transactions")
public class StockTransactionController {

    private final StockTransactionService service;

    public StockTransactionController(StockTransactionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "流水分页（维度/来源单/类型/方向/时间筛选 + 方向合计）")
    public R<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String bizDocType,
            @RequestParam(required = false) String bizDocNo,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String binCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, bizDocType, bizDocNo, typeCode, direction,
                itemCode, warehouseCode, batchNo, binCode, from, to, keyword));
    }

    @GetMapping("/by-doc")
    @Operation(summary = "按来源单据下钻（作业台详情抽屉）")
    public R<List<InvTransaction>> byDoc(@RequestParam String bizDocType,
                                         @RequestParam String bizDocNo) {
        return R.ok(service.byBizDoc(bizDocType, bizDocNo));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.FifoStrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 先进先出（4.6.1，spec outbound-strategy）：B 推荐试算（只读）+ C 偏离监控台账。
 * 查询认证即可（菜单管可见、接口管可为）；落账在领料创建与 4.6.3 确认入口强制。
 */
@Tag(name = "出库策略-先进先出")
@RestController
@RequestMapping("/api/inv/fifo")
public class FifoStrategyController {

    private final FifoStrategyService service;

    public FifoStrategyController(FifoStrategyService service) {
        this.service = service;
    }

    @GetMapping("/simulate")
    @Operation(summary = "B 试算：FIFO+FEFO 批次推荐（只读模拟，不占库存）")
    public R<Map<String, Object>> simulate(@RequestParam String warehouseCode,
                                           @RequestParam String itemCode,
                                           @RequestParam java.math.BigDecimal qty,
                                           @RequestParam(defaultValue = "false") boolean binLevel) {
        return R.ok(service.simulate(warehouseCode, itemCode, qty, binLevel));
    }

    @GetMapping("/deviations")
    @Operation(summary = "C 偏离监控：改批台账分页（单据/物料/操作人/时间筛选）")
    public R<Map<String, Object>> deviations(@RequestParam(required = false) String docNo,
                                             @RequestParam(required = false) String itemCode,
                                             @RequestParam(required = false) String createBy,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo,
                                             @RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.deviations(docNo, itemCode, createBy, dateFrom, dateTo, current, size));
    }
}

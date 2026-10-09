package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.ExpiryPriorityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 效期优先（4.6.2，spec outbound-strategy）：黄/橙/红预警清单 + 锁定标识（只读查询）。
 */
@Tag(name = "出库策略-效期优先")
@RestController
@RequestMapping("/api/inv/expiry")
public class ExpiryPriorityController {

    private final ExpiryPriorityService service;

    public ExpiryPriorityController(ExpiryPriorityService service) {
        this.service = service;
    }

    @GetMapping("/warnings")
    @Operation(summary = "效期预警清单（黄≤90/橙≤60/红≤30 + 已锁定标识）")
    public R<Map<String, Object>> warnings(@RequestParam(required = false) String itemCode,
                                           @RequestParam(required = false) String warehouseCode,
                                           @RequestParam(required = false) String level,
                                           @RequestParam(required = false) Boolean locked,
                                           @RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.warnings(itemCode, warehouseCode, level, locked, current, size));
    }
}

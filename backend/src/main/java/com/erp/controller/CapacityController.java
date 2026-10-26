package com.erp.controller;

import com.erp.common.R;
import com.erp.service.mrp.CapacityLoadService;
import com.erp.service.mrp.CapacityLoadService.LoadRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 产能负荷查询接口（change add-dispatch-management，spec capacity-load，
 * 5.6.2 负荷监控 / 5.6.3 外协建议共用读口径）。只读，登录即可。
 */
@Tag(name = "产能负荷")
@RestController
@RequestMapping("/api/mrp/capacity")
public class CapacityController {

    private final CapacityLoadService service;

    public CapacityController(CapacityLoadService service) {
        this.service = service;
    }

    @GetMapping("/loads")
    @Operation(summary = "WC×日期负荷", description = "负荷率=RELEASED 派工占用÷日历班次可用；"
            + "无可用有占用=999；超 CAPACITY_LOAD_TRIGGER 标 overLimit（实时读参）")
    public R<List<LoadRow>> loads(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String wcCode) {
        return R.ok(service.loads(from, to, wcCode));
    }
}

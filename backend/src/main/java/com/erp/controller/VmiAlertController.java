package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.vmi.VmiAlert;
import com.erp.service.vmi.VmiAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * VMI 告警/建议（2.8.1，spec vmi-consignment）。
 * 查询 ADMIN/PM/WAREHOUSE；确认放行仅 ADMIN/PM（SecurityConfig /api/proc/vmi/** 细规则）。
 */
@Tag(name = "VMI 告警与建议")
@RestController
@RequestMapping("/api/proc/vmi/alerts")
public class VmiAlertController {

    private final VmiAlertService service;

    public VmiAlertController(VmiAlertService service) {
        this.service = service;
    }

    @Operation(summary = "告警分页（类型/状态/供应商筛选）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String alertType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String supplierId) {
        return R.ok(service.page(current, size, alertType, status, supplierId));
    }

    @Operation(summary = "超水位告警确认放行（ADMIN/PM，OPEN→CONFIRMED 留痕）")
    @PostMapping("/{id}/confirm")
    public R<VmiAlert> confirm(@PathVariable String id,
                               @RequestBody(required = false) Map<String, Object> body) {
        Object opinion = body == null ? null : body.get("opinion");
        return R.ok(service.confirm(id, opinion == null ? null : String.valueOf(opinion)));
    }

    @Operation(summary = "补货建议惰性扫描（台账加载/结算生成时亦会自动触发）")
    @PostMapping("/scan")
    public R<Void> scan() {
        service.scanReplenish();
        return R.ok(null);
    }
}

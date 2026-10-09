package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkRelease;
import com.erp.service.sd.FrameworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 销售框架协议接口（tasks 13.7，3.11.1 框架订单 / 3.11.2 分批发货执行视图，
 * spec sales-framework-agreement）。权限：SALES / SALES_MGR / SALES_DIRECTOR / WAREHOUSE / ADMIN。
 */
@Tag(name = "销售框架协议")
@RestController
@RequestMapping("/api/sd/frameworks")
public class FrameworkController {

    private final FrameworkService service;

    public FrameworkController(FrameworkService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "协议分页")
    public R<Page<Framework>> page(@RequestParam(defaultValue = "1") long current,
                                   @RequestParam(defaultValue = "10") long size,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String customerId) {
        return R.ok(service.page(current, size, keyword, status, customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "详情（行三量余量 + 下达单 + 变更历史）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/execution")
    @Operation(summary = "3.11.2 分批执行视图（余量/已发/执行时间表）")
    public R<Map<String, Object>> execution(@PathVariable String id) {
        return R.ok(service.execution(id));
    }

    @PostMapping
    @Operation(summary = "创建协议（FW_NO 生成后锁定，13.2）")
    public R<Framework> create(@RequestBody Map<String, Object> req) {
        return R.ok(service.create(req));
    }

    @PostMapping("/{id}/update")
    @Operation(summary = "维护协议（编号不可改；已下达行不可改量价）")
    public R<Framework> update(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return R.ok(service.update(id, req));
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "下达框架订单（回写已下达量，超量 C-4.3-10 阻断，13.3）")
    public R<FrameworkRelease> release(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        String lineId = str(body.get("lineId"));
        BigDecimal qty = body.get("qty") == null ? null : new BigDecimal(String.valueOf(body.get("qty")));
        LocalDate deliverDate = body.get("deliverDate") == null
                || String.valueOf(body.get("deliverDate")).isBlank() ? null
                : LocalDate.parse(String.valueOf(body.get("deliverDate")));
        return R.ok(service.release(id, lineId, qty, deliverDate));
    }

    @PostMapping("/releases/{releaseId}/cancel")
    @Operation(summary = "取消未发货的下达单（回退已下达量）")
    public R<FrameworkRelease> cancelRelease(@PathVariable String releaseId,
                                             @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return R.ok(service.cancelRelease(releaseId, reason));
    }

    @PostMapping("/releases/{releaseId}/ship")
    @Operation(summary = "执行视图发起分批发货（过账回写已发量，13.6）")
    public R<Map<String, Object>> ship(@PathVariable String releaseId,
                                       @RequestBody Map<String, Object> body) {
        BigDecimal qty = body.get("qty") == null ? null : new BigDecimal(String.valueOf(body.get("qty")));
        return R.ok(service.shipFromRelease(releaseId, qty));
    }

    @PostMapping("/{id}/change")
    @Operation(summary = "变更/终止（总量/单价/终止，销售总监 L2 审批，S-4.3-11）")
    public R<Framework> change(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.change(id, payload));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

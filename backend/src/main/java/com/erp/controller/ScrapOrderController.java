package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.service.inv.ScrapOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报废出库单（4.5.4，spec scrap-order）。查询认证即可，写限 ADMIN/WAREHOUSE；
 * 会签经审批底座待办签署（ApprovalController），本控制器只管提交与进度查询。
 */
@Tag(name = "报废出库单")
@RestController
@RequestMapping("/api/inv/scrap-orders")
public class ScrapOrderController {

    private final ScrapOrderService service;

    public ScrapOrderController(ScrapOrderService service) {
        this.service = service;
    }

    public static class SavePayload {
        public InvScrapOrder head;
        public List<InvScrapOrderLine> lines;
    }

    @PostMapping
    @Operation(summary = "创建报废单（DRAFT，按原因分流门槛）")
    public R<Map<String, Object>> create(@RequestBody SavePayload payload) {
        return R.ok(service.create(payload.head, payload.lines));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑报废单（仅 DRAFT）")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody SavePayload payload) {
        return R.ok(service.update(id, payload.head, payload.lines));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "作废（仅 DRAFT，原因必填）")
    public R<Void> cancel(@PathVariable String id, @RequestParam String reason) {
        service.cancel(id, reason);
        return R.ok(null);
    }

    @PostMapping("/{id}/submit-approval")
    @Operation(summary = "提交三方会签（仅 STALE，C-4.4-14）")
    public R<Map<String, Object>> submitApproval(@PathVariable String id) {
        return R.ok(service.submitForApproval(id));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "简化批准（QUALITY/DAMAGE/OTHER；STALE 422 须会签）")
    public R<Map<String, Object>> approve(@PathVariable String id) {
        return R.ok(service.approve(id));
    }

    @PostMapping("/{id}/post")
    @Operation(summary = "出库过账（引擎 SCRAP_OUT + 凭证一）")
    public R<Map<String, Object>> post(@PathVariable String id) {
        return R.ok(service.post(id));
    }

    @PostMapping("/{id}/dispose")
    @Operation(summary = "处置核销（凭证二 → DISPOSED）")
    public R<Map<String, Object>> dispose(@PathVariable String id) {
        return R.ok(service.dispose(id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "详情（头+行）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping
    @Operation(summary = "报废单列表（status/keyword 过滤）")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }
}

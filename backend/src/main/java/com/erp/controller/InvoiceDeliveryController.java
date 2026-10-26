package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.service.fin.InvoiceDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 发票交付管理（M9-2-4，FR-4.14-2-4）。
 */
@Tag(name = "发票交付")
@RestController
@RequestMapping("/api/fin/invoice-deliveries")
@RequiredArgsConstructor
public class InvoiceDeliveryController {

    private final InvoiceDeliveryService service;

    @Operation(summary = "销项交付手工登记（票号须存在于销项台账）")
    @PostMapping
    public R<InvoiceDelivery> register(@RequestBody Map<String, Object> payload) {
        return R.ok(service.register(payload));
    }

    @Operation(summary = "交付列表（含超期黄标 overdue 字段）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String source) {
        return R.ok(service.list(status, source));
    }

    @Operation(summary = "标记已发送（回填时间置 SENT）")
    @PostMapping("/{id}/mark-sent")
    public R<InvoiceDelivery> markSent(@PathVariable String id) {
        return R.ok(service.markSent(id));
    }

    @Operation(summary = "重发（计数+1；超上限 422）")
    @PostMapping("/{id}/resend")
    public R<InvoiceDelivery> resend(@PathVariable String id) {
        return R.ok(service.resend(id));
    }
}

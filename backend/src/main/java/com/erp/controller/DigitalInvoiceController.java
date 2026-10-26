package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.DecodedRequest;
import com.erp.entity.fin.DigitalQuota;
import com.erp.service.fin.DigitalInvoiceService;
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
 * 数电发票管理（M9-2-3，spec invoice-management，离线模式）。
 */
@Tag(name = "数电发票管理")
@RestController
@RequestMapping("/api/fin/digital-invoices")
@RequiredArgsConstructor
public class DigitalInvoiceController {

    private final DigitalInvoiceService service;

    @Operation(summary = "授信额度登记（upsert 一期一行）")
    @PostMapping("/quota")
    public R<DigitalQuota> saveQuota(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveQuota(payload));
    }

    @Operation(summary = "某期额度（含可用余额）")
    @GetMapping("/quota")
    public R<Map<String, Object>> quota(@RequestParam(required = false) String period) {
        return R.ok(service.quota(period));
    }

    @Operation(summary = "提交开票请求（额度不足 422 BR-4.14-36；幂等已开 422 BR-4.14-37）")
    @PostMapping("/requests")
    public R<DecodedRequest> submitRequest(@RequestBody Map<String, Object> payload) {
        return R.ok(service.submitRequest(payload));
    }

    @Operation(summary = "请求队列（按提交时序）")
    @GetMapping("/requests")
    public R<List<DecodedRequest>> listRequests(@RequestParam(required = false) String status) {
        return R.ok(service.listRequests(status));
    }

    @Operation(summary = "补开（扣额度+超限自动拆分+自动建交付行；重复 422）")
    @PostMapping("/requests/{id}/issue")
    public R<Map<String, Object>> issue(@PathVariable String id) {
        return R.ok(service.issue(id));
    }

    @Operation(summary = "月末逐票勾稽（漏开/重开/金额差异清单）")
    @GetMapping("/reconcile")
    public R<Map<String, Object>> reconcile(@RequestParam(required = false) String period) {
        return R.ok(service.reconcile(period));
    }
}

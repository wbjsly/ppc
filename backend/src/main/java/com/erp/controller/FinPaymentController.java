package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.service.fin.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 付款申请与执行（2.7.3，spec payment-request + payment-execution）。
 * 查询/申请/审批提交 ADMIN+PM；排期、待付款标记、执行付款仅 ADMIN（SecurityConfig 细规则 + 服务层双校验）。
 */
@Tag(name = "付款管理")
@RestController
@RequestMapping("/api/fin/payments")
public class FinPaymentController {

    private final PaymentService service;

    public FinPaymentController(PaymentService service) {
        this.service = service;
    }

    @Operation(summary = "申请分页（供应商/状态/单号/日期区间，含审批节点）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(service.page(current, size, supplierId, status, keyword, dateFrom, dateTo));
    }

    @Operation(summary = "核销记录（KIND=PAYMENT/SETTLE 通用）")
    @GetMapping("/writeoffs")
    public R<Page<Map<String, Object>>> writeoffs(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String kind,
                                                  @RequestParam(required = false) String supplierId,
                                                  @RequestParam(required = false) String invoiceNo) {
        return R.ok(service.writeoffPage(current, size, kind, supplierId, invoiceNo));
    }

    @Operation(summary = "可选发票（该供应商 POSTED 且未清 >0）")
    @GetMapping("/invoice-candidates")
    public R<List<Map<String, Object>>> invoiceCandidates(
            @RequestParam(required = false) String supplierId) {
        return R.ok(service.invoiceCandidates(supplierId));
    }

    @Operation(summary = "可抵扣扣款单（该供应商 TO_DEDUCT 的 SCAR 扣款单）")
    @GetMapping("/deduct-candidates")
    public R<List<Map<String, Object>>> deductCandidates(
            @RequestParam(required = false) String supplierId) {
        return R.ok(service.deductCandidates(supplierId));
    }

    @Operation(summary = "申请详情（发票/审批/付款/核销）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建付款申请（金额 ≤ 所选发票未清合计，冻结供应商 422）")
    @PostMapping
    public R<FinPaymentRequest> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "提交分级审批（≤5万 PM / ≤50万 +财务主管 / >50万 +总经理）")
    @PostMapping("/{id}/submit")
    public R<FinPaymentRequest> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "作废（仅草稿/被驳回，原因必填）")
    @PostMapping("/{id}/cancel")
    public R<FinPaymentRequest> cancel(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.cancel(id, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "排期（仅 ADMIN：计划付款日必填）")
    @PostMapping("/{id}/schedule")
    public R<FinPaymentRequest> schedule(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        Object planDate = body.get("planDate");
        return R.ok(service.schedule(id, planDate == null ? null : String.valueOf(planDate)));
    }

    @Operation(summary = "待付款标记/恢复（仅 ADMIN）")
    @PostMapping("/{id}/wait-funds")
    public R<FinPaymentRequest> waitFunds(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        boolean wait = !Boolean.FALSE.equals(body.get("wait"));
        return R.ok(service.setWaitFunds(id, wait));
    }

    @Operation(summary = "执行付款（仅 ADMIN：超审批金额 L1 / 余额校验 / FIFO 核销 / SCAR 抵扣）")
    @PostMapping("/{id}/execute")
    public R<Map<String, Object>> execute(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        body.put("reqId", id);
        return R.ok(service.execute(body));
    }
}

package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.SdReturn;
import com.erp.service.sd.ReturnService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

/**
 * 销售退货接口（tasks 12.8，3.10.1 退货申请 / 3.10.2 退货判定 / 3.10.3 退款换货，
 * spec sales-return）。权限：SALES / SALES_MGR / FINANCE_MGR / WAREHOUSE / ADMIN。
 * 与采购退货 /api/proc/returns（ReturnController）独立。
 */
@Tag(name = "销售退货")
@RestController
@RequestMapping("/api/sd/returns")
public class SdReturnController {

    private final ReturnService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public SdReturnController(ReturnService service) {
        this.service = service;
    }

    @GetMapping("/returnables")
    @Operation(summary = "SO 行可退余量（已发 - 已退 + 开票状态，12.2）")
    public R<Map<String, Object>> returnables(@RequestParam String soId) {
        return R.ok(service.returnables(soId));
    }

    @PostMapping
    @Operation(summary = "创建退货申请（数量≤可退余量、开票状态分流、凭证说明必填）")
    public R<SdReturn> createApply(@RequestBody Map<String, Object> req) {
        return R.ok(service.createApply(req));
    }

    @GetMapping
    @Operation(summary = "退货单分页")
    public R<Page<SdReturn>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String customerId) {
        return R.ok(service.page(current, size, keyword, status, customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "全链路详情（申请/判定/审批/红冲换货/入库）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/judge")
    @Operation(summary = "退货判定（责任方/核定数量/超期/依据，12.3）")
    public R<SdReturn> judge(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.judge(id, payload));
    }

    @PostMapping("/{id}/judge-reject")
    @Operation(summary = "判定驳回（注明原因退回）")
    public R<SdReturn> judgeReject(@PathVariable String id,
                                   @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return R.ok(service.judgeReject(id, reason));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批（退款 L2：销售经理+财务；换货：销售经理）")
    public R<SdReturn> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "退款执行（红字发票+应收红冲+凭证；部分退款支付登记，12.5）")
    public R<Map<String, Object>> refund(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        BigDecimal amt = body.get("refundAmt") == null
                || String.valueOf(body.get("refundAmt")).isBlank() ? null
                : new BigDecimal(String.valueOf(body.get("refundAmt")));
        LocalDate date = body.get("refundDate") == null
                || String.valueOf(body.get("refundDate")).isBlank() ? null
                : LocalDate.parse(String.valueOf(body.get("refundDate")));
        String payNo = body.get("payNo") == null ? null : String.valueOf(body.get("payNo"));
        return R.ok(service.refund(id, amt, date, payNo));
    }

    @PostMapping("/{id}/exchange")
    @Operation(summary = "换货执行（新发货单重走 ATP 锁批，库存不足阻断，12.6）")
    public R<Map<String, Object>> exchange(@PathVariable String id) {
        return R.ok(service.exchange(id));
    }

    @PostMapping("/{id}/stock-in")
    @Operation(summary = "实物入库（回补 AVAILABLE_QTY；qc=1 入待检不计 ATP，12.7）")
    public R<Map<String, Object>> stockIn(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> items = body.get("items") == null ? List.of()
                : mapper.convertValue(body.get("items"),
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        return R.ok(service.stockIn(id, items));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "撤销退货单（仅草稿/已驳回）")
    public R<SdReturn> cancel(@PathVariable String id,
                              @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return R.ok(service.cancel(id, reason));
    }
}

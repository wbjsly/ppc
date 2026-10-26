package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.InputInvoice;
import com.erp.service.fin.InputInvoiceService;
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
 * 进项发票管理（M9-2-2，spec invoice-management）。
 */
@Tag(name = "进项发票管理")
@RestController
@RequestMapping("/api/fin/input-invoices")
@RequiredArgsConstructor
public class InputInvoiceController {

    private final InputInvoiceService service;

    @Operation(summary = "进项登记（PO 三单匹配自动判定；供应商不符 422）")
    @PostMapping
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> payload) {
        return R.ok(service.register(payload));
    }

    @Operation(summary = "进项列表（认证状态/匹配状态/供应商/期间筛选；含临期黄标数据源）")
    @GetMapping
    public R<List<InputInvoice>> list(@RequestParam(required = false) String certStatus,
                                       @RequestParam(required = false) String matchStatus,
                                       @RequestParam(required = false) String supplierId,
                                       @RequestParam(required = false) String period) {
        return R.ok(service.list(certStatus, matchStatus, supplierId, period));
    }

    @Operation(summary = "查验留痕登记（外部查验 OOS，登记即回传）")
    @PostMapping("/{id}/inspect")
    public R<InputInvoice> inspect(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.inspect(id,
                String.valueOf(payload.getOrDefault("method", "MANUAL")),
                String.valueOf(payload.getOrDefault("result", "PASS"))));
    }

    @Operation(summary = "认证登记（重复 422/超差 422/超期自动不可抵扣/所属期锁定）")
    @PostMapping("/{id}/certify")
    public R<InputInvoice> certify(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.certify(id, String.valueOf(payload.get("certDate"))));
    }

    @Operation(summary = "生成本期进项凭证（DRAFT 三态+TAXIN 锚+幂等）")
    @PostMapping("/generate-vouchers")
    public R<Map<String, Object>> generateVouchers(@RequestParam String period) {
        return R.ok(service.generateVouchers(period));
    }

    @Operation(summary = "临期未认证预警清单（C-4.14-07 预警）")
    @GetMapping("/nearing-expiry")
    public R<List<InputInvoice>> nearingExpiry() {
        return R.ok(service.nearingExpiry());
    }
}

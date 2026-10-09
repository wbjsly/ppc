package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.sd.InvoiceApply;
import com.erp.service.fin.InvoiceService;
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

import java.math.BigDecimal;
import java.util.Map;

/**
 * 销售开票接口（tasks 10.9，3.8.1 开票触发，spec sales-invoicing-receivable）。
 * 权限：FINANCE_MGR / ADMIN（SecurityConfig /api/fin/** 规则 + 服务层二次校验）。
 */
@Tag(name = "销售开票")
@RestController
@RequestMapping("/api/fin/invoice-applies")
public class InvoiceApplyController {

    private final InvoiceService service;

    public InvoiceApplyController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "开票申请分页（状态/客户/SO/关键字）")
    public R<Page<InvoiceApply>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String customerId,
                                      @RequestParam(required = false) String soNo) {
        return R.ok(service.page(current, size, status, keyword, customerId, soNo));
    }

    @GetMapping("/{id}")
    @Operation(summary = "申请详情（含发货行、发票、应收与税务资质实时结论）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PutMapping("/{id}/amount")
    @Operation(summary = "手工调整开票金额（提交时按容差校验）")
    public R<InvoiceApply> updateAmount(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("applyAmount");
        BigDecimal amount = null;
        if (raw != null) {
            try {
                amount = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ignore) {
                // 由服务层报错
            }
        }
        return R.ok(service.updateAmount(id, amount, str(body.get("remark"))));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审核（税务资质 C-4.3-06 L1 + 金额容差校验）")
    public R<InvoiceApply> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/audit")
    @Operation(summary = "财务审核（通过 / 退回修改）")
    public R<InvoiceApply> audit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pass = !Boolean.FALSE.equals(body.get("pass"));
        return R.ok(service.audit(id, pass, str(body.get("opinion"))));
    }

    @PostMapping("/{id}/issue")
    @Operation(summary = "发票开具（外部开票系统桩 + 回写应收与 SO）")
    public R<Map<String, Object>> issue(@PathVariable String id) {
        return R.ok(service.issue(id));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "客户确认发票（记录确认人与时间）")
    public R<InvoiceApply> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    @PostMapping("/{id}/dispute")
    @Operation(summary = "客户异议（协商处理或转红字）")
    public R<InvoiceApply> dispute(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.dispute(id, body.get("note")));
    }

    // ---------- 销项发票与红字发票台账 ----------

    @GetMapping("/invoices")
    @Operation(summary = "销项发票台账分页")
    public R<Page<SalesInvoice>> invoicePage(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String customerId) {
        return R.ok(service.invoicePage(current, size, status, keyword, customerId));
    }

    @GetMapping("/invoices/{id}")
    @Operation(summary = "发票详情")
    public R<SalesInvoice> invoice(@PathVariable String id) {
        return R.ok(service.getInvoice(id));
    }

    @PostMapping("/red-invoices")
    @Operation(summary = "红字发票开具与应收红冲（关联原票与退货单）")
    public R<RedInvoice> redInvoice(@RequestBody Map<String, Object> body) {
        Object raw = body.get("amount");
        BigDecimal amount = null;
        if (raw != null) {
            try {
                amount = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ignore) {
                // 由服务层报错
            }
        }
        return R.ok(service.redInvoice(str(body.get("originInvoiceId")),
                str(body.get("returnId")), str(body.get("returnNo")),
                amount, str(body.get("reason"))));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

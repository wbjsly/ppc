package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArReceipt;
import com.erp.entity.fin.ArStatement;
import com.erp.entity.fin.ArWriteoff;
import com.erp.service.fin.ArService;
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
 * 应收核销与对账接口（tasks 10.9，3.8.2 自动核销 / 3.8.3 应收管理，
 * spec sales-invoicing-receivable）。权限：FINANCE_MGR / ADMIN。
 */
@Tag(name = "应收核销")
@RestController
@RequestMapping("/api/fin/ar")
public class ArController {

    private final ArService service;

    public ArController(ArService service) {
        this.service = service;
    }

    // ---------- 3.8.3 应收台账 ----------

    @GetMapping
    @Operation(summary = "应收台账多维查询（客户/SO/发票号/合同/超期）")
    public R<Page<ArInvoice>> arPage(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String customerId,
                                     @RequestParam(required = false) String soNo,
                                     @RequestParam(required = false) String contractId,
                                     @RequestParam(required = false) Boolean overdue) {
        return R.ok(service.arPage(current, size, keyword, status, customerId,
                soNo, contractId, overdue));
    }

    @GetMapping("/{id}")
    @Operation(summary = "应收详情（明细行 + 核销记录 + 所属合同期次与收款进度）")
    public R<Map<String, Object>> arDetail(@PathVariable String id) {
        return R.ok(service.arDetail(id));
    }

    // ---------- 3.8.2 自动核销（回款 FIFO + 人工兜底） ----------

    @PostMapping("/receipts")
    @Operation(summary = "回款登记（FIFO 自动核销：最早应收优先、支持部分核销）")
    public R<Map<String, Object>> registerReceipt(@RequestBody Map<String, Object> body) {
        Object raw = body.get("amount");
        BigDecimal amount = null;
        if (raw != null) {
            try {
                amount = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ignore) {
                // 由服务层报错
            }
        }
        LocalDate payDate = null;
        if (body.get("payDate") != null && !String.valueOf(body.get("payDate")).isBlank()) {
            payDate = LocalDate.parse(String.valueOf(body.get("payDate")));
        }
        return R.ok(service.registerReceipt(str(body.get("customerId")), amount, payDate,
                str(body.get("remark"))));
    }

    @PostMapping("/writeoffs/manual")
    @Operation(summary = "人工核销（无法自动匹配转人工，spec FR-4.3-7-5 异常分支）")
    public R<Map<String, Object>> manualWriteoff(@RequestBody Map<String, Object> body) {
        Object raw = body.get("amount");
        BigDecimal amount = null;
        if (raw != null) {
            try {
                amount = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ignore) {
                // 由服务层报错
            }
        }
        LocalDate payDate = null;
        if (body.get("payDate") != null && !String.valueOf(body.get("payDate")).isBlank()) {
            payDate = LocalDate.parse(String.valueOf(body.get("payDate")));
        }
        return R.ok(service.manualWriteoff(str(body.get("receiptId")), str(body.get("arId")),
                amount, payDate, str(body.get("remark"))));
    }

    @GetMapping("/receipts")
    @Operation(summary = "回款单分页（含待人工核销队列）")
    public R<Page<ArReceipt>> receiptPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String customerId) {
        return R.ok(service.receiptPage(current, size, status, keyword, customerId));
    }

    @GetMapping("/writeoffs")
    @Operation(summary = "核销明细分页（核销人/时间/匹配明细）")
    public R<Page<ArWriteoff>> writeoffPage(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String customerId,
                                            @RequestParam(required = false) String arNo) {
        return R.ok(service.writeoffPage(current, size, keyword, customerId, arNo));
    }

    // ---------- 月度对账（10.7） ----------

    @PostMapping("/statements")
    @Operation(summary = "生成月度对账单（期初/本期开票/本期核销/期末 + 未核销明细）")
    public R<Map<String, Object>> generateStatement(@RequestBody Map<String, String> body) {
        return R.ok(service.generateStatement(body.get("customerId"), body.get("period")));
    }

    @GetMapping("/statements")
    @Operation(summary = "对账单分页")
    public R<Page<ArStatement>> statementPage(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String customerId) {
        return R.ok(service.statementPage(current, size, keyword, customerId));
    }

    @GetMapping("/statements/{id}")
    @Operation(summary = "对账单详情（未核销逐笔明细 + 差异排查记录）")
    public R<Map<String, Object>> statementDetail(@PathVariable String id) {
        return R.ok(service.statementDetail(id));
    }

    @PostMapping("/statements/lines/{lineId}/check")
    @Operation(summary = "差异逐笔排查记录")
    public R<Void> checkLine(@PathVariable String lineId, @RequestBody Map<String, Object> body) {
        BigDecimal diff = null;
        Object raw = body.get("diffAmt");
        if (raw != null) {
            try {
                diff = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException ignore) {
                // 由服务层报错
            }
        }
        service.checkLine(lineId, str(body.get("checkStatus")), str(body.get("checkNote")), diff);
        return R.ok();
    }

    // ---------- 计划达成对比（10.8） ----------

    @GetMapping("/plan-progress/{contractId}")
    @Operation(summary = "计划达成对比（逐期计划金额/实际应收/已核销/达成率，超期标红）")
    public R<List<Map<String, Object>>> planProgress(@PathVariable String contractId) {
        return R.ok(service.planProgress(contractId));
    }

    @GetMapping("/contract-summary/{contractId}")
    @Operation(summary = "合同名下应收汇总（合同 → SO → 应收链路）")
    public R<Map<String, Object>> contractSummary(@PathVariable String contractId) {
        return R.ok(service.contractArSummary(contractId));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.SalesInvoice;
import com.erp.service.SysParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销项发票台账（M9-2-1 税务视角，FR-4.14-2-5 销项台账 + BR-4.14-15 限额提示）。
 * 申请/审核/开具链复用既有 sd_invoice_apply（sales-invoicing-receivable），此处仅台账与提示。
 */
@Tag(name = "销项发票台账")
@RestController
@RequestMapping("/api/fin/sales-invoice-ledger")
@RequiredArgsConstructor
public class SalesInvoiceLedgerController {

    private final SalesInvoiceDao salesInvoiceDao;
    private final SysParamService sysParamService;

    @Operation(summary = "销项台账（期间筛选+金额税额汇总）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String period,
                                       @RequestParam(required = false) String status) {
        LambdaQueryWrapper<SalesInvoice> q = new LambdaQueryWrapper<SalesInvoice>()
                .eq(SalesInvoice::getDelFlag, "0")
                .orderByDesc(SalesInvoice::getInvoiceDate);
        if (period != null && !period.isBlank()) {
            q.apply("DATE_FORMAT(INVOICE_DATE, '%Y%m') = {0}", period.trim());
        }
        if (status != null && !status.isBlank()) {
            q.eq(SalesInvoice::getStatus, status.trim().toUpperCase());
        }
        List<SalesInvoice> rows = salesInvoiceDao.selectList(q);
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (SalesInvoice si : rows) {
            if (si.getAmount() != null) {
                totalAmount = totalAmount.add(si.getAmount());
            }
            if (si.getTaxAmount() != null) {
                totalTax = totalTax.add(si.getTaxAmount());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("totalAmount", totalAmount);
        out.put("totalTax", totalTax);
        return R.ok(out);
    }

    @Operation(summary = "限额提示校验（BR-4.14-15 L4：超限返回建议拆分数，不阻断）")
    @GetMapping("/limit-check")
    public R<Map<String, Object>> limitCheck(@RequestParam BigDecimal amount) {
        BigDecimal limit = sysParamService.getAmount("INVOICE_MAX_AMOUNT", new BigDecimal("100000"));
        boolean over = amount.compareTo(limit) > 0;
        int suggested = over
                ? amount.divide(limit, 0, RoundingMode.CEILING).intValue() : 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limit", limit);
        out.put("overLimit", over);
        out.put("suggestedSplit", suggested);
        out.put("hint", over ? "建议拆分为 " + suggested + " 张（单张限额 " + limit + "，BR-4.14-15）" : null);
        return R.ok(out);
    }
}

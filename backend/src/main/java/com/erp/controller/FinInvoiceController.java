package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinApInvoice;
import com.erp.service.fin.ThreeWayMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 三方匹配（2.7.2，spec three-way-match）。
 * 发票登记/匹配/确认放行 ADMIN、PM；跨期手工过账仅 ADMIN（SecurityConfig + 服务层双校验）。
 */
@Tag(name = "三方匹配")
@RestController
@RequestMapping("/api/fin/invoices")
public class FinInvoiceController {

    private final ThreeWayMatchService service;

    public FinInvoiceController(ThreeWayMatchService service) {
        this.service = service;
    }

    @Operation(summary = "发票分页（状态/供应商/发票号/日期区间，含匹配状态与差异率）")
    @GetMapping
    public R<Page<Map<String, Object>>> invoicePage(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String supplierId,
                                                    @RequestParam(required = false) String dateFrom,
                                                    @RequestParam(required = false) String dateTo) {
        return R.ok(service.invoicePage(current, size, keyword, status, supplierId, dateFrom, dateTo));
    }

    @Operation(summary = "匹配台账（状态/供应商/发票号/PO 筛选）")
    @GetMapping("/matches")
    public R<Page<Map<String, Object>>> matchPage(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String supplierId,
                                                  @RequestParam(required = false) String invoiceNo,
                                                  @RequestParam(required = false) String poNo) {
        return R.ok(service.matchPage(current, size, status, supplierId, invoiceNo, poNo));
    }

    @Operation(summary = "匹配单详情（逐行差异明细 + 凭证）")
    @GetMapping("/matches/{matchId}")
    public R<Map<String, Object>> matchDetail(@PathVariable String matchId) {
        return R.ok(service.matchDetail(matchId));
    }

    @Operation(summary = "发票详情（行 + 关联匹配单）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> invoiceDetail(@PathVariable String id) {
        return R.ok(service.invoiceDetail(id));
    }

    @Operation(summary = "发票登记（头 + 行；同供应商发票号重复 422）")
    @PostMapping
    public R<FinApInvoice> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createInvoice(payload));
    }

    @Operation(summary = "执行三单匹配（容差内自动冲回暂估 + 转正式应付；超容差冻结生成异常对账单）")
    @PostMapping("/{id}/match")
    public R<Map<String, Object>> match(@PathVariable String id) {
        return R.ok(service.match(id));
    }

    @Operation(summary = "采购员确认价差（必填意见；非跨期确认即过账，跨期待手工过账）")
    @PostMapping("/matches/{matchId}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String matchId,
                                          @RequestBody Map<String, Object> body) {
        Object opinion = body.get("opinion");
        return R.ok(service.confirm(matchId, opinion == null ? null : String.valueOf(opinion)));
    }

    @Operation(summary = "跨期手工过账（仅 ADMIN，生成当期价差调整凭证并注明跨期原因）")
    @PostMapping("/matches/{matchId}/manual-post")
    public R<Map<String, Object>> manualPost(@PathVariable String matchId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Object note = body == null ? null : body.get("note");
        return R.ok(service.manualPost(matchId, note == null ? null : String.valueOf(note)));
    }
}

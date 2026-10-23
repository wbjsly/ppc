package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.CashDoc;
import com.erp.service.fin.CashDocService;
import com.erp.service.fin.CashWriteoffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 统一收付款中心（M8-4-1/8.4-2，spec payment-collection）。
 * 创建/过账/作废 FINANCE_MGR+；人工核销 FINANCE_MGR+（服务层双校验）。
 */
@Tag(name = "收付管理")
@RestController
@RequestMapping("/api/fin/cash-docs")
public class CashDocController {

    private final CashDocService service;
    private final CashWriteoffService writeoffService;

    public CashDocController(CashDocService service, CashWriteoffService writeoffService) {
        this.service = service;
        this.writeoffService = writeoffService;
    }

    @Operation(summary = "分页（方向/状态/往来对象/关键字/日期区间）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String direction,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String partyId,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(service.page(current, size, direction, status, partyId, keyword, dateFrom, dateTo));
    }

    @Operation(summary = "详情（含核销明细）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建收付款单（IN 收款 / OUT 付款）")
    @PostMapping
    public R<CashDoc> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "过账（余额 + 资金凭证 + FIFO 核销，跨期挂审批）")
    @PostMapping("/{id}/post")
    public R<Map<String, Object>> post(@PathVariable String id) {
        return R.ok(service.post(id));
    }

    @Operation(summary = "作废（仅草稿/待过账；已过账走红字冲销）")
    @PostMapping("/{id}/void")
    public R<CashDoc> voidDoc(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.voidDoc(id, body.get("reason")));
    }

    @Operation(summary = "付款执行（排期申请 → OUT 单创建+过账，旧 API 兼容形态）")
    @PostMapping("/execute-out")
    public R<Map<String, Object>> executeOut(@RequestBody Map<String, Object> payload) {
        return R.ok(service.executeOut(payload));
    }

    @Operation(summary = "人工核销（手动指定目标单据；跨期挂审批）")
    @PostMapping("/{id}/manual-settle")
    public R<Map<String, Object>> manualSettle(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (RuntimeException e) {
            amount = null;
        }
        return R.ok(writeoffService.manualSettle(id, String.valueOf(body.get("targetType")),
                String.valueOf(body.get("targetId")), amount));
    }

    @Operation(summary = "单据核销明细")
    @GetMapping("/{id}/writeoffs")
    public R<List<Map<String, Object>>> writeoffs(@PathVariable String id) {
        return R.ok(writeoffService.listByDoc(id));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.TaxCalcLedger;
import com.erp.service.fin.TaxCalcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 计税管理（M9-1，spec tax-calculation）：9.1.1 税码匹配 / 9.1.2 税额计算 / 9.1.3 合规校验。
 */
@Tag(name = "计税管理")
@RestController
@RequestMapping("/api/fin/tax-calc")
@RequiredArgsConstructor
public class TaxCalcController {

    private final TaxCalcService service;

    @Operation(summary = "税码匹配（唯一自动选定/多命中候选列表/C-4.14-01 422）")
    @GetMapping("/match")
    public R<Map<String, Object>> match(@RequestParam String trxType,
                                        @RequestParam String scope,
                                        @RequestParam String calcType,
                                        @RequestParam(required = false) String rateKind,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return R.ok(service.match(trxType, scope, calcType, rateKind, businessDate));
    }

    @Operation(summary = "税额计算（价税分离/多税率折扣分摊/简易不抵扣/尾差 overTolerance）")
    @PostMapping("/calc")
    public R<Map<String, Object>> calc(@RequestBody Map<String, Object> payload) {
        return R.ok(service.calc(payload));
    }

    @Operation(summary = "台账落库（RECORDED 留痕）")
    @PostMapping("/ledger")
    public R<Map<String, Object>> ledger(@RequestBody Map<String, Object> payload) {
        return R.ok(service.ledger(payload));
    }

    @Operation(summary = "台账列表（筛选：状态/交易类型/税码/期间）")
    @GetMapping("/ledger")
    public R<List<TaxCalcLedger>> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String trxType,
                                       @RequestParam(required = false) String taxCode,
                                       @RequestParam(required = false) String period) {
        return R.ok(service.list(status, trxType, taxCode, period));
    }

    @Operation(summary = "补录对手方/期间/摘要 → 入账申请（APPLYING）")
    @PostMapping("/ledger/{id}/submit-apply")
    public R<TaxCalcLedger> submitApply(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.submitApply(id,
                String.valueOf(payload.getOrDefault("counterparty", "")),
                String.valueOf(payload.getOrDefault("period", "")),
                String.valueOf(payload.getOrDefault("summary", ""))));
    }

    @Operation(summary = "生成税务凭证（DRAFT 三态，TAXCALC 锚；重复生成 422）")
    @PostMapping("/ledger/{id}/generate-voucher")
    public R<Map<String, Object>> generateVoucher(@PathVariable String id) {
        return R.ok(service.generateVoucher(id));
    }

    @Operation(summary = "合规校验报告（过期引用/分类编码黄标/总账勾稽/抵扣违规）")
    @GetMapping("/report")
    public R<Map<String, Object>> report(@RequestParam(required = false) String period) {
        return R.ok(service.complianceReport(period));
    }
}

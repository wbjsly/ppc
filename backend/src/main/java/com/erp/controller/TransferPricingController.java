package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.TpAssessment;
import com.erp.entity.fin.TpDocument;
import com.erp.entity.fin.TpParty;
import com.erp.service.fin.TransferPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 关联交易定价管理（9.6.3，transfer-pricing，S-4.14-08）。
 */
@Tag(name = "关联交易定价")
@RestController
@RequestMapping("/api/fin/tp")
@RequiredArgsConstructor
public class TransferPricingController {

    private final TransferPricingService service;

    // ---------- 关联方 ----------

    @PostMapping("/parties")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "登记关联方（重复 422 / 境外标注）")
    public R<TpParty> registerParty(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerParty(payload));
    }

    @GetMapping("/parties")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "关联方列表")
    public R<List<TpParty>> listParties() {
        return R.ok(service.listParties());
    }

    // ---------- 定价文档 ----------

    @PostMapping("/documents")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "登记年度定价文档（方法五族校验 422）")
    public R<TpDocument> registerDocument(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerDocument(payload));
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "文档列表（按关联方/年度）")
    public R<List<TpDocument>> listDocuments(@RequestParam(required = false) String partyId,
                                             @RequestParam(required = false) String coverYear) {
        return R.ok(service.listDocuments(partyId, coverYear));
    }

    @GetMapping("/reminders")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "备查临期 + 年度文档断档提醒（查询型）")
    public R<Map<String, Object>> reminders() {
        return R.ok(service.reminders());
    }

    // ---------- 风险自评 ----------

    @PostMapping("/assessments")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "年度特别纳税调整风险自评（留痕不阻断）")
    public R<TpAssessment> submitAssessment(@RequestBody Map<String, Object> payload) {
        return R.ok(service.submitAssessment(payload));
    }

    @GetMapping("/assessments")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "自评历史")
    public R<List<TpAssessment>> listAssessments() {
        return R.ok(service.listAssessments());
    }
}

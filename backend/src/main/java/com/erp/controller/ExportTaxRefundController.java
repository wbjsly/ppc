package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.ExportDeal;
import com.erp.entity.fin.ExportRefundApply;
import com.erp.service.fin.ExportTaxRefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 出口退（免）税接口（M9-5-3，spec export-tax-refund）。
 */
@RestController
@RequestMapping("/api/fin/export-refunds")
@RequiredArgsConstructor
@Tag(name = "出口退税（M9-5-3）")
public class ExportTaxRefundController {

    private final ExportTaxRefundService service;

    // ---------- 一票一档（FR-4.14-7-1/2/3） ----------

    @PostMapping("/deals")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "一票一档登记（FOB 折算 BR-41 + 退税率 + 不一致 L4 预警）")
    public R<Map<String, Object>> registerDeal(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerDeal(payload));
    }

    @GetMapping("/deals")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "一票一档分页")
    public R<Page<ExportDeal>> dealPage(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String refundApplyNo) {
        return R.ok(service.dealPage(current, size, keyword, refundApplyNo));
    }

    @GetMapping("/deals/{id}/documents")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "单证齐全检查（三单 + 临期/超期 + 视同内销测算）")
    public R<Map<String, Object>> checkDocuments(@PathVariable String id) {
        return R.ok(service.checkDocuments(id));
    }

    @PostMapping("/deals/{id}/exemption")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "单证豁免登记（L2 豁免原因留痕）")
    public R<Map<String, Object>> exemption(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        return R.ok(Map.of("dealNo", service.approveExemption(id, body.get("reason")).getDealNo(),
                "exempted", true));
    }

    @PostMapping("/deals/{id}/collect")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "单证收齐登记（docDecl/docInvoice/docReceipt 置 1）")
    public R<Map<String, Object>> collect(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        ExportDeal d = service.collectDocuments(id, body);
        return R.ok(Map.of("dealNo", d.getDealNo(), "docDecl", d.getDocDecl(),
                "docInvoice", d.getDocInvoice(), "docReceipt", d.getDocReceipt()));
    }

    // ---------- 退税申报（FR-4.14-7-4/5） ----------

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "创建退税申报单（聚合多票；单证门禁；免抵退勾稽 BR-44）")
    public R<Map<String, Object>> createApply(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createApply(payload));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "提交 L2 审批")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submitApply(id));
    }

    @PostMapping("/{id}/register")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申报状态登记（受理/审核/核准/退回补正+清单/驳回）")
    public R<Map<String, Object>> register(@PathVariable String id,
                                           @RequestBody Map<String, Object> payload) {
        return R.ok(service.registerStatus(id, payload));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "退税款到账（一致核销+收款凭证；差异挂账 BR-45）")
    public R<Map<String, Object>> receive(@PathVariable String id,
                                          @RequestBody Map<String, Object> payload) {
        return R.ok(service.receive(id, payload));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "退税申报单分页")
    public R<Page<ExportRefundApply>> applyPage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String status) {
        return R.ok(service.applyPage(current, size, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申报单详情（含审批轨迹与所辖档案）")
    public R<Map<String, Object>> applyDetail(@PathVariable String id) {
        return R.ok(service.applyDetail(id));
    }

    @GetMapping("/warnings")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "分级预警（截止前 60/30 天 + 超期未申报清单）")
    public R<Map<String, Object>> warnings() {
        return R.ok(service.warnings());
    }
}

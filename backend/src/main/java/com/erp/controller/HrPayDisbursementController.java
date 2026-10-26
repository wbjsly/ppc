package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrPayDiff;
import com.erp.entity.hr.HrPayDisbursement;
import com.erp.entity.hr.HrPayExecution;
import com.erp.entity.hr.HrPayReceipt;
import com.erp.service.hr.HrPayDisbursementService;
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

import java.util.List;
import java.util.Map;

/** 薪酬发放与对账（payroll-disbursement M10-6 三叶：代发文件/发放执行/回单对账）。 */
@Tag(name = "薪酬发放")
@RestController
@RequestMapping("/api/hr/payroll/disbursement")
@RequiredArgsConstructor
public class HrPayDisbursementController {

    private final HrPayDisbursementService disbService;

    // ---------- 10.6.1 代发文件 ----------

    @PostMapping("/files")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "生成代发文件（缺卡阻断/合计校验/重复生成须作废；网银推送留桩=下载）")
    public R<Map<String, Object>> generateFile(@RequestBody Map<String, Object> payload) {
        HrPayDisbursement d = disbService.generateFile(String.valueOf(payload.get("runId")), payload);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("file", d);
        return R.ok(out);
    }

    @PostMapping("/files/{id}/void")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "作废代发文件")
    public R<HrPayDisbursement> voidFile(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(disbService.voidFile(id, String.valueOf(payload.get("reason"))));
    }

    @GetMapping("/files")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "代发文件清单")
    public R<List<HrPayDisbursement>> files(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) String runId) {
        return R.ok(disbService.fileList(period, runId));
    }

    // ---------- 10.6.2 发放执行 ----------

    @PostMapping("/executions")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','HR_MGR','ADMIN')")
    @Operation(summary = "出纳登记发放（合计校验/大额提示/PAID/PARTIAL）")
    public R<HrPayExecution> registerExecution(@RequestBody Map<String, Object> payload) {
        return R.ok(disbService.registerExecution(payload));
    }

    @GetMapping("/executions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "发放登记清单")
    public R<List<HrPayExecution>> executions(@RequestParam String runId) {
        return R.ok(disbService.executionList(runId));
    }

    // ---------- 10.6.3 回单对账 ----------

    @PostMapping("/receipts")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','HR','HR_MGR','ADMIN')")
    @Operation(summary = "回单登记（手工；自动获取留桩）")
    public R<HrPayReceipt> registerReceipt(@RequestBody Map<String, Object> payload) {
        return R.ok(disbService.registerReceipt(payload));
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','HR','HR_MGR','ADMIN')")
    @Operation(summary = "逐笔对账（金额精确匹配；全匹配→RECONCILED）")
    public R<Map<String, Object>> reconcile(@RequestBody Map<String, Object> payload) {
        return R.ok(disbService.reconcile(String.valueOf(payload.get("runId"))));
    }

    @GetMapping("/diffs")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "差异清单")
    public R<List<HrPayDiff>> diffs(@RequestParam(required = false) String runId,
                                    @RequestParam(required = false) String status) {
        return R.ok(disbService.diffList(runId, status));
    }

    @PostMapping("/diffs/{id}/resolve")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "差异处理（三方案；金额调整待 L2）")
    public R<HrPayDiff> resolveDiff(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(disbService.resolveDiff(id, payload));
    }

    @PostMapping("/diffs/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_MGR','FINANCE_MGR','ADMIN')")
    @Operation(summary = "差异金额调整 L2 审批")
    public R<HrPayDiff> approveDiff(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(disbService.approveDiff(id, payload));
    }

    @PostMapping("/archive")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "归档（RECONCILED+差异清零 → 凭证包锁定）")
    public R<Map<String, Object>> archive(@RequestBody Map<String, Object> payload) {
        return R.ok(disbService.archive(String.valueOf(payload.get("runId"))));
    }

    @GetMapping("/recon-board")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "对账进度看板")
    public R<Map<String, Object>> reconBoard(@RequestParam String runId) {
        return R.ok(disbService.reconBoard(runId));
    }
}

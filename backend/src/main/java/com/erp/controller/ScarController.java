package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.Scar;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.qms.ScarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * SCAR 供应商质量索赔（6.8.1 质量索赔 / 6.8.2 评分反馈，spec supplier-quality-claim）。
 * 扣款财务确认走 /api/qms/approvals（ScarFinance）；SENT 起强制加严由检验批取严推导读取。
 */
@Tag(name = "SCAR 供应商质量索赔")
@RestController
@RequestMapping("/api/qms/scar")
public class ScarController {

    private final ScarService service;

    public ScarController(ScarService service) {
        this.service = service;
    }

    @Operation(summary = "分页（状态/供应商/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String supplierId) {
        return R.ok(service.page(current, size, keyword, status, supplierId));
    }

    @Operation(summary = "详情（NCR/扣款单/审批）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "扣款单列表")
    @GetMapping("/{id}/deductions")
    public R<List<ScarDeduction>> deductions(@PathVariable String id) {
        return R.ok(service.deductions(id));
    }

    @Operation(summary = "供应商扣款汇总（评分数据源 BR-4.12-42）")
    @GetMapping("/deduction-summary")
    public R<Map<String, Object>> deductionSummary(@RequestParam(required = false) String supplierId) {
        return R.ok(service.deductionSummary(supplierId));
    }

    @Operation(summary = "手工发起（NCR_RETURN / REPEAT / STOPPAGE）")
    @PostMapping
    public R<Scar> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.createDraft(body));
    }

    @Operation(summary = "发出（重大 ≥1 万走质量经理审批，其余直接 SENT + 5 工作日时限）")
    @PostMapping("/{id}/submit")
    public R<Scar> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "SQE 代录 8D 回复（浅层根因退回计数）")
    @PostMapping("/{id}/reply")
    public R<Scar> reply(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object text = body.get("replyText");
        return R.ok(service.recordReply(id, text == null ? null : String.valueOf(text)));
    }

    @Operation(summary = "验证回复（PASS 关闭 / FAIL 退回重发时限）")
    @PostMapping("/{id}/verify")
    public R<Scar> verify(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        Object conclusion = body.get("conclusion");
        return R.ok(service.verify(id, pass, conclusion == null ? "" : String.valueOf(conclusion)));
    }

    @Operation(summary = "生成扣款单（金额 ≤ 索赔额）")
    @PostMapping("/{id}/deductions")
    public R<ScarDeduction> createDeduction(@PathVariable String id,
                                            @RequestBody Map<String, Object> body) {
        BigDecimal amount = null;
        if (body.get("amount") != null) {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        }
        Object remark = body.get("remark");
        return R.ok(service.createDeduction(id, amount,
                remark == null ? null : String.valueOf(remark)));
    }

    @Operation(summary = "扣款财务确认（底座 ScarFinance 单签 ADMIN 代）")
    @PostMapping("/deductions/{deductionId}/finance")
    public R<ScarDeduction> submitFinance(@PathVariable String deductionId) {
        return R.ok(service.submitFinance(deductionId));
    }

    @Operation(summary = "扣款推送待抵扣（CONFIRMED → TO_DEDUCT + 应付抵扣桩 D3）")
    @PostMapping("/deductions/{deductionId}/to-deduct")
    public R<ScarDeduction> toDeduct(@PathVariable String deductionId) {
        return R.ok(service.markToDeduct(deductionId));
    }

    @Operation(summary = "扣款争议暂挂/解除")
    @PostMapping("/deductions/{deductionId}/dispute")
    public R<ScarDeduction> dispute(@PathVariable String deductionId,
                                    @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.dispute(deductionId, reason == null ? "" : String.valueOf(reason)));
    }

    @Operation(summary = "回复超期扫描（测试与运维手动触发）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        return R.ok(Map.of("replyOverdue", service.sweepReplyOverdue()));
    }
}

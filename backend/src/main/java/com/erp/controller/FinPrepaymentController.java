package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinPrepayment;
import com.erp.service.fin.PrepaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 预付款（2.7.3，spec prepayment，BR-4.2-52 / C-4.2-13）。
 * 执行预付仅 ADMIN；申请/审批/手动冲抵 ADMIN+PM。
 */
@Tag(name = "预付款")
@RestController
@RequestMapping("/api/fin/prepayments")
public class FinPrepaymentController {

    private final PrepaymentService service;

    public FinPrepaymentController(PrepaymentService service) {
        this.service = service;
    }

    @Operation(summary = "分页（供应商/PO/状态）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String supplierId,
                                             @RequestParam(required = false) String poNo,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, supplierId, poNo, status));
    }

    @Operation(summary = "预付款清理待办（冻结供应商 + 未核销预付）")
    @GetMapping("/cleanup-todos")
    public R<List<Map<String, Object>>> cleanupTodos() {
        return R.ok(service.cleanupTodos());
    }

    @Operation(summary = "详情（含冲抵记录）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建预付款（C-4.2-13 双 L1：比例上限 / 累计 vs PO 未清）")
    @PostMapping
    public R<FinPrepayment> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "提交分级审批")
    @PostMapping("/{id}/submit")
    public R<FinPrepayment> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "作废（仅草稿/被驳回，原因必填）")
    @PostMapping("/{id}/cancel")
    public R<FinPrepayment> cancel(@PathVariable String id,
                                   @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.cancel(id, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "执行预付（仅 ADMIN：余额条件扣减 + 借 1123/贷 1002 凭证）")
    @PostMapping("/{id}/execute")
    public R<Map<String, Object>> execute(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        body.put("ppId", id);
        return R.ok(service.execute(body));
    }

    @Operation(summary = "手动冲抵应付（兜底幂等；发票 POSTED 后系统已自动触发）")
    @PostMapping("/settle")
    public R<Map<String, Object>> settle(@RequestParam(required = false) String poNo) {
        int n = service.settleForPo(poNo);
        return R.ok(Map.of("poNo", poNo == null ? "" : poNo, "settled", n));
    }
}

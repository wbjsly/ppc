package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.proc.ReturnOrder;
import com.erp.service.proc.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 退货单（2.6.1 质量退货，spec quality-return）。
 * 质量退货由 NCR 评审自动带出（BR-4.2-32）；手工退货须关联原入库单与单价。
 * 出库（postings）WAREHOUSE；审批签署走 /api/qms/approvals（ROLE_PM）。
 */
@Tag(name = "退货管理")
@RestController
@RequestMapping("/api/proc/returns")
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @Operation(summary = "分页（状态/来源/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sourceType) {
        return R.ok(service.page(current, size, keyword, status, sourceType));
    }

    @Operation(summary = "详情（行/审批/NCR/跟踪）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "手工发起（已入库须关联原入库单 + 原入库单价）")
    @PostMapping
    public R<ReturnOrder> createManual(@RequestBody Map<String, Object> body) {
        return R.ok(service.createManual(body));
    }

    @Operation(summary = "提交采购经理审批（驳回可改重提）")
    @PostMapping("/{id}/submit")
    public R<ReturnOrder> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "作废（仅草稿/被驳回；已出库只读归档）")
    @PostMapping("/{id}/cancel")
    public R<ReturnOrder> cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.cancel(id, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "退货出库（库存扣减锁定优先 + 红字凭证 RV + NCR 推进 + 30 天跟踪）")
    @PostMapping("/{id}/postings")
    public R<ReturnOrder> posting(@PathVariable String id) {
        return R.ok(service.posting(id));
    }

    @Operation(summary = "跟踪逾期扫描（测试与运维手动触发；调度器每小时自动跑）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        return R.ok(Map.of("trackOverdue", service.sweepTrackOverdue()));
    }

    @Operation(summary = "跟踪闭环（补发/退款完成留痕关闭）")
    @PostMapping("/{id}/track-close")
    public R<ReturnOrder> closeTrack(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object remark = body.get("remark");
        return R.ok(service.closeTrack(id, remark == null ? null : String.valueOf(remark)));
    }
}

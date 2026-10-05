package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.Ncr;
import com.erp.entity.qms.NcrLog;
import com.erp.service.qms.NcrService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * NCR 不合格品（2.5.3 不合格品 / 6.5.1 标识隔离 / 6.5.2 处置跟踪，spec ncr-management）。
 * 状态机：CREATED → 评审选定处置（RETURNING/SORTING/REWORKING/CONCESSION）
 *        → 处置凭证确认 DISPOSED → CAPA 有效 + 复检放行 CLOSED（解冻只读）。
 */
@Tag(name = "不合格品 NCR")
@RestController
@RequestMapping("/api/qms/ncrs")
public class NcrController {

    private final NcrService service;

    public NcrController(NcrService service) {
        this.service = service;
    }

    @Operation(summary = "NCR 分页（状态/严重度/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String severity) {
        return R.ok(service.page(current, size, keyword, status, severity));
    }

    @Operation(summary = "NCR 详情（检验批快照/检验项/CAPA/复检批/退货与让步单/日志）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "操作日志（评审/处置/升级/关闭全链留痕）")
    @GetMapping("/{id}/logs")
    public R<List<NcrLog>> logs(@PathVariable String id) {
        return R.ok(service.logs(id));
    }

    @Operation(summary = "评审（必须选定处置；安全/法规 CTQ 禁让步 BR-4.12-26）")
    @PostMapping("/{id}/review")
    public R<Ncr> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.review(id,
                str(body.get("disposition")), str(body.get("opinion"))));
    }

    @Operation(summary = "处置方案录入（挑选/返工）")
    @PostMapping("/{id}/disposition-plan")
    public R<Ncr> plan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.planDisposition(id, str(body.get("plan"))));
    }

    @Operation(summary = "处置执行确认（凭证齐全 → DISPOSED；costAmount 非空同事务归集 COPQ 内部失败）")
    @PostMapping("/{id}/dispose-confirm")
    public R<Ncr> confirmDisposed(@PathVariable String id, @RequestBody Map<String, Object> body) {
        java.math.BigDecimal cost = null;
        if (body.get("costAmount") != null && !String.valueOf(body.get("costAmount")).isBlank()) {
            try {
                cost = new java.math.BigDecimal(String.valueOf(body.get("costAmount")));
            } catch (Exception e) {
                throw new com.erp.common.ServiceException(422, "costAmount 必须为数字");
            }
        }
        return R.ok(service.confirmDisposed(id, str(body.get("result")), cost));
    }

    @Operation(summary = "关闭（CAPA 有效 + 复检放行 → CLOSED 解冻只读）")
    @PostMapping("/{id}/close")
    public R<Ncr> close(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.close(id, str(body.get("opinion"))));
    }

    @Operation(summary = "作废（仅评审前，填原因；解冻）")
    @PostMapping("/{id}/cancel")
    public R<Ncr> cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.cancel(id, str(body.get("reason"))));
    }

    @Operation(summary = "超时/超期扫描（测试与运维手动触发；调度器每 5 分钟自动跑）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        int review = service.sweepReviewTimeouts();
        int overdue = service.sweepOverdueEscalation();
        return R.ok(Map.of("reviewEscalated", review, "overdueHandled", overdue));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

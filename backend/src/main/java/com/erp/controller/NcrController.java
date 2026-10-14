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

    @Operation(summary = "NCR 分页（状态/严重度/关键字；statuses 逗号分隔多态、frozenOnly 仅冻结中、withStage 附处置阶段）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String statuses,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(required = false) Boolean frozenOnly,
                                             @RequestParam(required = false) Boolean withStage) {
        return R.ok(service.page(current, size, keyword, status, statuses, severity, frozenOnly, withStage));
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

    @Operation(summary = "评审（必须选定处置；安全/法规 CTQ 禁让步 BR-4.12-26；CTQ 单提交转审批不流转）")
    @PostMapping("/{id}/review")
    public R<Ncr> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.review(id,
                str(body.get("disposition")), str(body.get("opinion"))));
    }

    @Operation(summary = "隔离确认（6.5.1：货位/贴标人/时间留痕，不改状态机）")
    @PostMapping("/{id}/isolate")
    public R<Ncr> isolate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.isolate(id, str(body.get("bin"))));
    }

    @Operation(summary = "针对已放行批次创建 NCR（6.1 漏检召回评估入口：校验 RELEASED+幂等，标记放行后发现）")
    @PostMapping("/released-lots")
    public R<Ncr> createForReleasedLot(@RequestBody Map<String, Object> body) {
        return R.ok(service.createForReleasedLot(
                str(body.get("lotId")), str(body.get("defectItem")),
                str(body.get("defectDesc")), str(body.get("severity")),
                str(body.get("ctqFlag"))));
    }

    @Operation(summary = "发起召回评估（6.2：仅放行后发现 NCR，建追溯单双向关联，不代执行冻结）")
    @PostMapping("/{id}/recall-evaluation")
    public R<Map<String, Object>> recallEvaluation(@PathVariable String id) {
        return R.ok(service.recallEvaluation(id));
    }

    @Operation(summary = "处置方案录入（挑选/返工；返工须结构化三字段 + CTQ 全含勾选）")
    @PostMapping("/{id}/disposition-plan")
    public R<Ncr> plan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.planDisposition(id, str(body.get("plan")),
                str(body.get("reworkSteps")), str(body.get("reworkParams")),
                str(body.get("reworkCheckReq")),
                body.get("ctqConfirmed") != null
                        && Boolean.parseBoolean(String.valueOf(body.get("ctqConfirmed")))));
    }

    @Operation(summary = "处置执行确认（凭证齐全 → DISPOSED；挑选四字段结构化+合计校验+低合格率软提示；costAmount 归集 COPQ）")
    @PostMapping("/{id}/dispose-confirm")
    public R<Map<String, Object>> confirmDisposed(@PathVariable String id, @RequestBody Map<String, Object> body) {
        java.math.BigDecimal cost = dec(body.get("costAmount"), "costAmount");
        java.math.BigDecimal sortTotal = dec(body.get("sortTotalQty"), "sortTotalQty");
        java.math.BigDecimal sortOk = dec(body.get("sortOkQty"), "sortOkQty");
        java.math.BigDecimal sortNg = dec(body.get("sortNgQty"), "sortNgQty");
        java.math.BigDecimal sortHours = dec(body.get("sortHours"), "sortHours");
        return R.ok(service.confirmDisposed(id, str(body.get("result")), cost,
                sortTotal, sortOk, sortNg, sortHours));
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

    @Operation(summary = "超时/超期/重复升级扫描（测试与运维手动触发；调度器每 5 分钟自动跑）")
    @PostMapping("/sweeps")
    public R<Map<String, Object>> sweeps() {
        int review = service.sweepReviewTimeouts();
        int overdue = service.sweepOverdueEscalation();
        int repeat = service.sweepRepeatNcrCapaTodos();
        return R.ok(Map.of("reviewEscalated", review, "overdueHandled", overdue,
                "repeatCapaTodos", repeat));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static java.math.BigDecimal dec(Object o, String field) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            throw new com.erp.common.ServiceException(422, field + " 必须为数字");
        }
    }
}

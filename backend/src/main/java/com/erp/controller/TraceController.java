package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.RecallService;
import com.erp.service.inv.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 追溯召回（4.13，spec trace-recall 菜单权限：菜单管可见、服务管可为）：
 * 4.13.1 发起/流向/冻结/拦截/召回登记（服务层质量三角色，结案限 MGR）、
 * 4.13.2 受限区入库（服务层 WAREHOUSE/ADMIN）；查询与审计仅需认证。
 */
@Tag(name = "库存管理-追溯召回")
@RestController
@RequestMapping("/api/inv/traces")
public class TraceController {

    private final TraceService traceService;
    private final RecallService recallService;

    public TraceController(TraceService traceService, RecallService recallService) {
        this.traceService = traceService;
        this.recallService = recallService;
    }

    @PostMapping("/analyze")
    @Operation(summary = "追溯发起（三索引归一，质量三角色）+ 流向自动物化")
    public R<Map<String, Object>> analyze(@RequestBody Map<String, Object> body) {
        return R.ok(traceService.analyze(str(body.get("indexType")),
                str(body.get("indexValue")), str(body.get("defectReason"))));
    }

    @GetMapping
    @Operation(summary = "追溯单分页（状态/关键字）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(traceService.page(status, keyword, current, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "追溯单详情（五类流向分组）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(traceService.detail(id));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "审计时间轴（按追溯单时间序回放，仅认证）")
    public R<List<Map<String, Object>>> logs(@PathVariable String id) {
        return R.ok(traceService.auditLogs(id));
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "批量冻结执行（复用 4.9 质量冻结链 + 释放预留）")
    public R<Map<String, Object>> freeze(@PathVariable String id) {
        return R.ok(traceService.freezeStock(id));
    }

    @PostMapping("/flows/{flowId}/intercept")
    @Operation(summary = "在途拦截登记（失败自动升级召回行）")
    public R<Map<String, Object>> intercept(@PathVariable String flowId,
                                            @RequestBody Map<String, Object> body) {
        boolean success = Boolean.parseBoolean(String.valueOf(body.getOrDefault("success", "false")));
        return R.ok(traceService.registerIntercept(flowId, success));
    }

    @PostMapping("/flows/{flowId}/return")
    @Operation(summary = "召回登记（实退/拒退，应召量定格）")
    public R<Map<String, Object>> registerReturn(@PathVariable String flowId,
                                                 @RequestBody Map<String, Object> body) {
        Object q = body.get("actualQty");
        BigDecimal actualQty = q == null || String.valueOf(q).isBlank()
                ? null : new BigDecimal(String.valueOf(q));
        return R.ok(traceService.registerReturn(flowId, actualQty,
                str(body.get("returnedBatch")), str(body.get("rejectReason"))));
    }

    @PostMapping("/receive")
    @Operation(summary = "召回受限区入库 RECALL_IN（WAREHOUSE/ADMIN，仅 RETURN/SCRAP 仓位）")
    public R<Map<String, Object>> receive(@RequestBody Map<String, Object> body) {
        Object q = body.get("qty");
        BigDecimal qty = q == null || String.valueOf(q).isBlank()
                ? null : new BigDecimal(String.valueOf(q));
        return R.ok(recallService.receive(str(body.get("traceId")), str(body.get("flowId")),
                str(body.get("warehouseCode")), str(body.get("binCode")), qty));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "结案报告（召回率，限 QUALITY_MGR/ADMIN）")
    public R<Map<String, Object>> close(@PathVariable String id) {
        return R.ok(traceService.close(id));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

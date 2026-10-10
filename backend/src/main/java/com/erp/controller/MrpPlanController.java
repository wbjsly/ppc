package com.erp.controller;

import com.erp.common.R;
import com.erp.service.mrp.MrpPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 生产管理-需求计划（change add-mrp-demand-planning，菜单 5.3.1~5.3.3，任务 7.1）。
 * 查询仅需认证；写操作（运行/审核/转正/处置）权限在服务层 requireAny 分流（PLANNER/ADMIN）；
 * 采购转正经服务层复用 mrp-auto-requisition 的 PR 生成链路。
 */
@Tag(name = "需求计划 MRP")
@RestController
@RequestMapping("/api/mrp")
public class MrpPlanController {

    private final MrpPlanService service;

    public MrpPlanController(MrpPlanService service) {
        this.service = service;
    }

    @Getter
    @Setter
    public static class ConfirmRequest {
        private BigDecimal confirmQty;
        private String confirmDate;
    }

    @Getter
    @Setter
    public static class IdsRequest {
        private List<String> ids;
    }

    // ---------- 运行 ----------

    @PostMapping("/runs")
    @Operation(summary = "触发正式 MRP 运行（范围 FULL/CATEGORY/GROUP 三选一；单运行互斥）")
    public R<Map<String, Object>> run(@RequestBody Map<String, String> body) {
        return R.ok(service.run(body.get("scopeType"), body.get("scopeValue")));
    }

    @GetMapping("/runs")
    @Operation(summary = "运行历史（RUN_NO/范围/时间/统计）")
    public R<List<Map<String, Object>>> runs() {
        return R.ok(service.runs());
    }

    // ---------- 建议查询（三菜单共用） ----------

    @GetMapping("/suggestions")
    @Operation(summary = "建议列表（type/status/runId/keyword 筛选；exceptionsOnly=true 聚合 EXCESS+OVERDUE，5.3.3）")
    public R<List<Map<String, Object>>> suggestions(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean exceptionsOnly) {
        return R.ok(service.suggestions(type, status, runId, keyword, exceptionsOnly));
    }

    // ---------- 审核生命周期 ----------

    @PostMapping("/suggestions/{id}/confirm")
    @Operation(summary = "确认建议（可改量/日期，原值留痕；EXCESS 拒绝；CAS）")
    public R<Map<String, Object>> confirm(@PathVariable String id,
                                          @RequestBody(required = false) ConfirmRequest req) {
        ConfirmRequest r = req == null ? new ConfirmRequest() : req;
        LocalDate date = r.getConfirmDate() == null || r.getConfirmDate().isBlank()
                ? null : LocalDate.parse(r.getConfirmDate());
        return R.ok(service.confirm(id, r.getConfirmQty(), date));
    }

    @PostMapping("/suggestions/{id}/cancel")
    @Operation(summary = "取消建议（原因必填）")
    public R<Void> cancel(@PathVariable String id, @RequestBody Map<String, String> body) {
        service.cancel(id, body.get("reason"));
        return R.ok();
    }

    // ---------- 转正 ----------

    @PostMapping("/suggestions/convert-pr")
    @Operation(summary = "批量生成请购单（复用自动请购链路：预检整批阻断 + PR 单号回填）")
    public R<Map<String, Object>> convertPr(@RequestBody IdsRequest req) {
        return R.ok(service.convertPr(req.getIds()));
    }

    @PostMapping("/suggestions/convert-mo")
    @Operation(summary = "批量转计划工单（PMO-YYYYMMDD-NNN 占位单号，5.4 对接）")
    public R<Map<String, Object>> convertMo(@RequestBody IdsRequest req) {
        return R.ok(service.convertMo(req.getIds()));
    }

    // ---------- 异常处置（5.3.3） ----------

    @PostMapping("/suggestions/{id}/handle")
    @Operation(summary = "异常标记已处理（仅 EXCESS/OVERDUE，备注必填，处理人/时间留痕）")
    public R<Void> handle(@PathVariable String id, @RequestBody Map<String, String> body) {
        service.handle(id, body.get("note"));
        return R.ok();
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.scm.CpfrAccuracyService;
import com.erp.service.scm.CpfrPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** CPFR 补货计划（spec cpfr-collaboration R5/R6/R7，菜单 12.8.2）。 */
@Tag(name = "CPFR-补货计划")
@RestController
@RequestMapping("/api/scm/cpfr")
public class CpfrPlanController {

    private final CpfrPlanService planService;
    private final CpfrAccuracyService accuracyService;

    public CpfrPlanController(CpfrPlanService planService, CpfrAccuracyService accuracyService) {
        this.planService = planService;
        this.accuracyService = accuracyService;
    }

    @Operation(summary = "计划清单（含行与 ZONE）")
    @GetMapping("/plans")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String agreementId,
                                             @RequestParam(required = false) String periodTag) {
        return R.ok(planService.list(agreementId, periodTag));
    }

    @Operation(summary = "创建联合补货计划（从已推送预测行生成，ZONE 标注）")
    @PostMapping("/plans")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(planService.create(payload));
    }

    @Operation(summary = "确认联合补货计划（side=SUPPLIER/INTERNAL，未达成例外 422）")
    @PostMapping("/plans/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id,
                                          @RequestBody Map<String, Object> payload) {
        String side = String.valueOf(payload.getOrDefault("side", "INTERNAL"));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return R.ok(planService.confirm(id, side, auth == null ? null : auth.getName()));
    }

    @Operation(summary = "冻结期/弹性调整（FREEZE/超弹性 → L2 PMGR 审批）")
    @PostMapping("/plan-lines/{lineId}/adjust")
    public R<Map<String, Object>> adjust(@PathVariable String lineId,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(planService.adjustLine(lineId, payload));
    }

    @Operation(summary = "转化登记（实际到货量）")
    @PostMapping("/plan-lines/{lineId}/convert")
    public R<Map<String, Object>> convert(@PathVariable String lineId,
                                          @RequestBody Map<String, Object> payload) {
        return R.ok(planService.convert(lineId, payload));
    }

    @Operation(summary = "生成 PO 跳转参数（URL 预填，不落采购表）")
    @GetMapping("/plan-lines/{lineId}/po-jump")
    public R<Map<String, Object>> poJump(@PathVariable String lineId) {
        return R.ok(planService.poJumpParams(lineId));
    }

    @Operation(summary = "月度准确率评估（连续 3 周期 <70% → 待复盘）")
    @PostMapping("/accuracy/{yyyyMM}/evaluate")
    public R<Map<String, Object>> evaluate(@PathVariable String yyyyMM) {
        return R.ok(accuracyService.evaluateMonth(yyyyMM));
    }
}

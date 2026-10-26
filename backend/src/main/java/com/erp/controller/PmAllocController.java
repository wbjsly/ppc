package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmAllocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 间接成本分摊（add-cost-collection-freeze，FR-4.8-5-5；11.6.1 Tab5）。 */
@Tag(name = "间接成本分摊")
@RestController
@RequestMapping("/api/pm/cost/allocs")
@RequiredArgsConstructor
public class PmAllocController {

    private final PmAllocService service;

    @GetMapping("/rules")
    @Operation(summary = "分摊规则列表")
    public R<List<Map<String, Object>>> listRules() {
        return R.ok(service.listRules());
    }

    @PostMapping("/rules")
    @Operation(summary = "保存规则（每项目至多一条生效，重复=覆盖）")
    public R<Map<String, Object>> saveRule(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveRule(payload));
    }

    @PostMapping("/rules/{id}/disable")
    @Operation(summary = "停用规则")
    public R<Void> disableRule(@PathVariable String id) {
        service.disableRule(id);
        return R.ok(null);
    }

    @GetMapping("/pools")
    @Operation(summary = "费用池列表（按期间）")
    public R<List<Map<String, Object>>> listPools(@RequestParam(required = false) String period) {
        return R.ok(service.listPools(period));
    }

    @PostMapping("/pools")
    @Operation(summary = "费用池录入")
    public R<Map<String, Object>> savePool(@RequestBody Map<String, Object> payload) {
        return R.ok(service.savePool(payload));
    }

    @GetMapping("/details")
    @Operation(summary = "分摊结果明细")
    public R<List<Map<String, Object>>> listDetails(@RequestParam String period) {
        return R.ok(service.listDetails(period));
    }

    @PostMapping("/run")
    @Operation(summary = "执行月度分摊（幂等：同期间同费用池跳过；缺规则项目 L4 提示）")
    public R<Map<String, Object>> run(@RequestParam String period) {
        return R.ok(service.runPeriod(period));
    }
}

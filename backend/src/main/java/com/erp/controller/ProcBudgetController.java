package com.erp.controller;

import com.erp.common.R;
import com.erp.service.proc.ProcBudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 年度采购预算（change add-framework-agreement-order，design D7）。
 * SecurityConfig：POST/PUT/DELETE /api/proc/** → ADMIN。
 */
@Tag(name = "采购预算")
@RestController
@RequestMapping("/api/proc/budgets")
public class ProcBudgetController {

    private final ProcBudgetService service;

    public ProcBudgetController(ProcBudgetService service) {
        this.service = service;
    }

    @Operation(summary = "预算列表", description = "按年度/品类科目检索，含累计、剩余与阈值（110%）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Integer year,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(service.list(year, keyword));
    }

    @Operation(summary = "预算使用视图", description = "预算行 ∪ 无预算有累计行（NO_BUDGET），供 2.3.5 展示")
    @GetMapping("/usage")
    public R<List<Map<String, Object>>> usage(@RequestParam(required = false) Integer year) {
        return R.ok(service.usage(year == null ? java.time.LocalDate.now().getYear() : year));
    }

    @Operation(summary = "录入预算", description = "同年度同科目唯一；立即生效（主数据基线）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload));
    }

    @Operation(summary = "修改预算", description = "变更原因必填，留痕前后值/操作人/时间")
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return R.ok(service.save(payload));
    }
}

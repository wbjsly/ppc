package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmHoursCollectService;
import com.erp.service.pm.PmHoursVarianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工时归集与偏差（add-timesheet-management，FR-4.8-4-3/4/5；
 * 11.5.3 归集 + 11.5.4 偏差；FINANCE 只读在 SecurityConfig GET /api/pm/hours/** 前缀）。
 */
@Tag(name = "工时归集与偏差")
@RestController
@RequestMapping("/api/pm/hours")
@RequiredArgsConstructor
public class PmHoursCollectController {

    private final PmHoursCollectService collectService;
    private final PmHoursVarianceService varianceService;

    @GetMapping("/collect/summary")
    @Operation(summary = "四级汇总（任务→包→阶段→项目）+ 预估对账")
    public R<Map<String, Object>> summary(@RequestParam(required = false) String projectId) {
        return R.ok(collectService.summary(projectId));
    }

    @GetMapping("/variance")
    @Operation(summary = "偏差树 + 预警列表")
    public R<Map<String, Object>> variance(@RequestParam(required = false) String projectId) {
        return R.ok(varianceService.variance(projectId));
    }

    @PostMapping("/variance/alerts/{id}/handle")
    @Operation(summary = "预警已处理标记（PM/PMO；服务层校验；幂等）")
    public R<Map<String, Object>> handleAlert(@PathVariable String id,
                                              @RequestParam(required = false) String note) {
        return R.ok(varianceService.handleAlert(id, note));
    }
}

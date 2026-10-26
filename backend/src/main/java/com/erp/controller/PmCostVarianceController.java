package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmCostVarianceService;
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

/** 成本归集台账与偏差监控（add-cost-collection-freeze，11.6.1 台账 + 11.6.2）。 */
@Tag(name = "成本归集与偏差监控")
@RestController
@RequestMapping("/api/pm/cost")
@RequiredArgsConstructor
public class PmCostVarianceController {

    private final PmCostCollectService collectService;
    private final PmCostVarianceService varianceService;

    @GetMapping("/collects")
    @Operation(summary = "五类归集台账（按项目/类别/期间筛选）")
    public R<List<Map<String, Object>>> listCollects(@RequestParam(required = false) String projectId,
                                                     @RequestParam(required = false) String costType,
                                                     @RequestParam(required = false) String dateFrom,
                                                     @RequestParam(required = false) String dateTo) {
        return R.ok(collectService.list(projectId, costType, dateFrom, dateTo));
    }

    @GetMapping("/collects/summary")
    @Operation(summary = "项目五分类合计")
    public R<Map<String, Object>> summary(@RequestParam String projectId) {
        return R.ok(collectService.summaryByType(projectId));
    }

    @GetMapping("/variance/{projectId}")
    @Operation(summary = "单项目偏差现算（PV 线性 + 五分类实际）")
    public R<Map<String, Object>> variance(@PathVariable String projectId) {
        return R.ok(varianceService.variance(projectId));
    }

    @PostMapping("/variance/scan")
    @Operation(summary = "手工触发日频扫描（03:40 任务同逻辑；PMO/财务补跑用）")
    public R<Map<String, Object>> scan() {
        return R.ok(varianceService.dailyScan());
    }

    @GetMapping("/alerts")
    @Operation(summary = "成本偏差预警清单")
    public R<List<Map<String, Object>>> listAlerts(@RequestParam(required = false) String projectId,
                                                   @RequestParam(required = false) String handledFlag) {
        return R.ok(varianceService.listAlerts(projectId, handledFlag));
    }

    @PostMapping("/alerts/{id}/handle")
    @Operation(summary = "预警「已处理」标记（可附说明）")
    public R<Map<String, Object>> markHandled(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> payload) {
        String note = payload == null ? null
                : payload.get("note") == null ? null : String.valueOf(payload.get("note"));
        return R.ok(varianceService.markHandled(id, note));
    }
}

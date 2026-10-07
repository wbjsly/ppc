package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfSlaMetric;
import com.erp.entity.intf.IntfSlaReport;
import com.erp.security.IntfGuard;
import com.erp.service.intf.SlaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** SLA 指标、告警升级与月报（spec interface-sla-monitoring，菜单 2.8.3「SLA 监控」）。 */
@Tag(name = "接口 SLA 监控")
@RestController
@RequestMapping("/api/intf/sla")
public class IntfSlaController {

    private final SlaService service;

    public IntfSlaController(SlaService service) {
        this.service = service;
    }

    @Operation(summary = "立即采集一轮指标（页面手动触发/测试用）")
    @PostMapping("/collect")
    public R<Integer> collect() {
        return R.ok(service.collect());
    }

    @Operation(summary = "指标分页（按指标/月份）")
    @GetMapping("/metrics")
    public R<Page<IntfSlaMetric>> metrics(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String metricKey,
                                          @RequestParam(required = false) String monthTag) {
        return R.ok(service.metrics(current, size, metricKey, monthTag));
    }

    @Operation(summary = "告警分页（级别/状态）")
    @GetMapping("/alerts")
    public R<Page<IntfSlaAlert>> alerts(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String level,
                                        @RequestParam(required = false) String status) {
        return R.ok(service.alerts(current, size, level, status));
    }

    @Operation(summary = "告警响应（记录响应人与时间）")
    @PostMapping("/alerts/{id}/respond")
    public R<Map<String, Object>> respond(@PathVariable String id) {
        return R.ok(service.respondAlert(id));
    }

    @Operation(summary = "月度报告分页")
    @GetMapping("/reports")
    public R<Page<IntfSlaReport>> reports(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status) {
        return R.ok(service.reports(current, size, status));
    }

    @Operation(summary = "生成月度报告（每月 1 日 02:00 自动，也可手动）")
    @PostMapping("/reports/generate")
    public R<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body) {
        Object month = body == null ? null : body.get("monthTag");
        return R.ok(service.generateReport(month == null ? null : String.valueOf(month)));
    }

    @Operation(summary = "审核报告（审核人不得为生成作业执行人）")
    @PostMapping("/reports/{id}/review")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object opinion = body.get("opinion");
        return R.ok(service.reviewReport(id, opinion == null ? null : String.valueOf(opinion)));
    }

    @Operation(summary = "归档报告（高危：仅 ADMIN）")
    @PostMapping("/reports/{id}/archive")
    public R<Map<String, Object>> archive(@PathVariable String id) {
        IntfGuard.requireAdmin("SLA 报告归档");
        return R.ok(service.archiveReport(id));
    }

    @Operation(summary = "对外发布（高危：仅 ADMIN；未归档硬阻断）")
    @PostMapping("/reports/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        IntfGuard.requireAdmin("SLA 报告对外发布");
        Object scope = body == null ? null : body.get("scope");
        return R.ok(service.publishReport(id, scope == null ? "全部合作方" : String.valueOf(scope)));
    }
}

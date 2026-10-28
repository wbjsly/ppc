package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.scm.CpfrDeadlineService;
import com.erp.service.scm.CpfrExceptionService;
import com.erp.service.scm.CpfrFeedbackService;
import com.erp.service.scm.CpfrForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** CPFR 协同预测（spec cpfr-collaboration R2/R3/R4，菜单 12.8.1）。 */
@Tag(name = "CPFR-协同预测")
@RestController
@RequestMapping("/api/scm/cpfr")
public class CpfrForecastController {

    private final CpfrForecastService forecastService;
    private final CpfrFeedbackService feedbackService;
    private final CpfrExceptionService exceptionService;
    private final CpfrDeadlineService deadlineService;

    public CpfrForecastController(CpfrForecastService forecastService,
                                  CpfrFeedbackService feedbackService,
                                  CpfrExceptionService exceptionService,
                                  CpfrDeadlineService deadlineService) {
        this.forecastService = forecastService;
        this.feedbackService = feedbackService;
        this.exceptionService = exceptionService;
        this.deadlineService = deadlineService;
    }

    // ---------- 预测版本 ----------

    @Operation(summary = "预测版本分页")
    @GetMapping("/forecasts")
    public R<Page<Map<String, Object>>> pageHeads(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String periodTag) {
        return R.ok(forecastService.pageHeads(current, size, periodTag));
    }

    @Operation(summary = "建预测版本（DRAFT）")
    @PostMapping("/forecasts")
    public R<Map<String, Object>> createHead(@RequestBody Map<String, Object> payload) {
        return R.ok(forecastService.createHead(payload));
    }

    @Operation(summary = "录/改 SKU×周预测行")
    @PostMapping("/forecasts/lines")
    public R<Map<String, Object>> saveLine(@RequestBody Map<String, Object> payload) {
        return R.ok(forecastService.saveLine(payload));
    }

    @Operation(summary = "预测行清单（含快照）")
    @GetMapping("/forecasts/{headId}/lines")
    public R<List<Map<String, Object>>> lines(@PathVariable String headId,
                                              @RequestParam(required = false) String agreementId) {
        return R.ok(forecastService.lines(headId, agreementId));
    }

    @Operation(summary = "生成数据包（自动快照库存/在途/安全库存）")
    @PostMapping("/forecasts/{headId}/generate-package")
    public R<Map<String, Object>> generatePackage(@PathVariable String headId) {
        return R.ok(forecastService.generatePackage(headId));
    }

    @Operation(summary = "按周期推送（协议未启用 422；幂等键）")
    @PostMapping("/forecasts/{headId}/push")
    public R<Map<String, Object>> push(@PathVariable String headId) {
        return R.ok(forecastService.pushCycle(headId));
    }

    // ---------- 反馈监控 ----------

    @Operation(summary = "周期反馈监控（惰性推进 48h/72h）")
    @GetMapping("/feedbacks/monitor")
    public R<List<Map<String, Object>>> monitor(@RequestParam(required = false) String agreementId,
                                                @RequestParam(required = false) String periodTag) {
        return R.ok(feedbackService.monitor(agreementId, periodTag));
    }

    @Operation(summary = "时限判定手动触发（evaluateAll）")
    @PostMapping("/deadlines/evaluate")
    public R<Map<String, Object>> evaluateAll() {
        return R.ok(deadlineService.evaluateAll());
    }

    // ---------- 例外协商 ----------

    @Operation(summary = "例外清单（含调整历史）")
    @GetMapping("/exceptions")
    public R<List<Map<String, Object>>> exceptions(@RequestParam(required = false) String agreementId,
                                                   @RequestParam(required = false) String status) {
        return R.ok(exceptionService.list(agreementId, status));
    }

    @Operation(summary = "计划员终确认例外（accept/reject）")
    @PostMapping("/exceptions/{id}/final-confirm")
    public R<Map<String, Object>> finalConfirm(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        boolean accept = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("accept", "false")));
        return R.ok(exceptionService.finalConfirm(id, accept,
                String.valueOf(payload.getOrDefault("remark", ""))));
    }
}

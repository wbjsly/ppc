package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.bi.BiExtractTask;
import com.erp.service.bi.ExtractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 抽取批次与就绪状态（spec bi-data-pipeline：窗口外不自动跑，手动补跑除外）。 */
@Tag(name = "BI 抽取批次")
@RestController
@RequestMapping("/api/bi/extract")
public class BiExtractController {

    private final ExtractService service;

    public BiExtractController(ExtractService service) {
        this.service = service;
    }

    @Operation(summary = "批次任务列表")
    @GetMapping("/batch")
    public R<List<BiExtractTask>> batch(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(date);
        return R.ok(service.batchTasks(d));
    }

    @Operation(summary = "手动补跑一批（绕过窗口，幂等推进 DAG）")
    @PostMapping("/run")
    public R<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        String date = body == null ? null : (String) body.get("date");
        LocalDate d = date == null || date.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(date);
        return R.ok(service.runBatch(d, true));
    }

    @Operation(summary = "数据就绪状态（分析页数据截止时间）")
    @GetMapping("/ready")
    public R<Map<String, Object>> ready() {
        return R.ok(service.readySnapshot());
    }

    @Operation(summary = "波动暂停人工确认")
    @PostMapping("/tasks/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirmPause(id));
    }
}

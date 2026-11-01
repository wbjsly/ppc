package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.DataExtractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报表数据抽取控制台（add-data-extract-console，spec bi-extract-console）：
 * 数据源登记 / 模板驱动配置 / 批次执行（DAG、波动确认、就绪）。
 */
@Tag(name = "BI 数据抽取")
@RestController
@RequestMapping("/api/bi/data-extract")
public class DataExtractController {

    private final DataExtractService service;

    public DataExtractController(DataExtractService service) {
        this.service = service;
    }

    // ---------- 数据源 ----------

    @Operation(summary = "数据源列表")
    @GetMapping("/datasources")
    public R<List<Map<String, Object>>> datasources() {
        return R.ok(service.listDatasources());
    }

    @Operation(summary = "新建数据源（连接信息加密存储）")
    @PostMapping("/datasources")
    public R<Map<String, Object>> createDatasource(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createDatasource(payload));
    }

    @Operation(summary = "修改数据源")
    @PutMapping("/datasources/{id}")
    public R<Map<String, Object>> updateDatasource(@PathVariable String id,
                                                   @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateDatasource(id, payload));
    }

    @Operation(summary = "删除数据源（有配置不可删）")
    @DeleteMapping("/datasources/{id}")
    public R<Void> deleteDatasource(@PathVariable String id) {
        service.deleteDatasource(id);
        return R.ok(null);
    }

    @Operation(summary = "连通性测试")
    @PostMapping("/datasources/{id}/test-connection")
    public R<Map<String, Object>> testConnection(@PathVariable String id) {
        return R.ok(service.testConnection(id));
    }

    @Operation(summary = "schema 快照比对（变更 → 暂停其下配置，BR-4.10-15）")
    @PostMapping("/datasources/{id}/check-schema")
    public R<Map<String, Object>> checkSchema(@PathVariable String id) {
        return R.ok(service.checkSchema(id));
    }

    @Operation(summary = "更新快照并恢复 ACTIVE")
    @PostMapping("/datasources/{id}/refresh-snapshot")
    public R<Map<String, Object>> refreshSnapshot(@PathVariable String id) {
        return R.ok(service.refreshSnapshot(id));
    }

    // ---------- 抽取配置 ----------

    @Operation(summary = "抽取配置列表")
    @GetMapping("/configs")
    public R<List<Map<String, Object>>> configs(
            @RequestParam(required = false) String datasourceId) {
        return R.ok(service.listConfigs(datasourceId));
    }

    @Operation(summary = "新建抽取配置（仅绑已发布模板）")
    @PostMapping("/configs")
    public R<Map<String, Object>> createConfig(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createConfig(payload));
    }

    @Operation(summary = "修改抽取配置")
    @PutMapping("/configs/{id}")
    public R<Map<String, Object>> updateConfig(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateConfig(id, payload));
    }

    @Operation(summary = "删除抽取配置（有执行记录改停用）")
    @DeleteMapping("/configs/{id}")
    public R<Void> deleteConfig(@PathVariable String id) {
        service.deleteConfig(id);
        return R.ok(null);
    }

    // ---------- 批次执行 ----------

    @Operation(summary = "批次任务列表")
    @GetMapping("/runs")
    public R<List<Map<String, Object>>> runs(@RequestParam(required = false) String date) {
        return R.ok(service.batchRuns(parseDate(date)));
    }

    @Operation(summary = "执行/推进批次（manual=true 手动补跑绕过窗口）")
    @PostMapping("/batch/run")
    public R<Map<String, Object>> runBatch(@RequestBody(required = false) Map<String, Object> body) {
        String date = body == null ? null : (String) body.get("date");
        boolean manual = body == null || !Boolean.FALSE.equals(body.get("manual"));
        return R.ok(service.runBatch(parseDate(date), manual));
    }

    @Operation(summary = "波动暂停人工确认继续（BR-4.10-12）")
    @PostMapping("/runs/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirmRun(id));
    }

    @Operation(summary = "暂停任务人工放弃终止")
    @PostMapping("/runs/{id}/abort")
    public R<Map<String, Object>> abort(@PathVariable String id) {
        return R.ok(service.abortRun(id));
    }

    @Operation(summary = "数据就绪状态")
    @GetMapping("/ready")
    public R<Map<String, Object>> ready() {
        return R.ok(service.readySnapshot());
    }

    private LocalDate parseDate(String date) {
        return date == null || date.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(date);
    }
}

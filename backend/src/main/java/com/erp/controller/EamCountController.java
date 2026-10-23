package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamCountPlan;
import com.erp.entity.eam.EamCountTeamMember;
import com.erp.service.eam.EamCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资产盘点（add-asset-count，spec asset-count）。
 * 7.5.1 盘点工作台：计划/分组/下发快照/抽样预览/清单/实盘录入/导入导出/覆盖率/基准日后变动/结案报告。
 */
@Tag(name = "资产盘点")
@RestController
@RequestMapping("/api/eam/counts")
public class EamCountController {

    private final EamCountService service;

    public EamCountController(EamCountService service) {
        this.service = service;
    }

    @Operation(summary = "盘点单分页")
    @GetMapping
    public R<Page<EamCountPlan>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String countType) {
        return R.ok(service.page(current, size, status, countType));
    }

    @Operation(summary = "盘点单详情（含分组/差异汇总/覆盖率）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建盘点计划（范围/基准日/方式）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody EamCountPlan plan) {
        return R.ok(service.create(plan));
    }

    @Operation(summary = "编辑计划（仅草稿）")
    @PutMapping
    public R<Map<String, Object>> update(@RequestBody EamCountPlan plan) {
        return R.ok(service.update(plan));
    }

    @Operation(summary = "维护盘点人员分组（覆盖式）")
    @PostMapping("/{id}/team")
    public R<Map<String, Object>> maintainTeam(@PathVariable String id,
                                               @RequestBody List<EamCountTeamMember> members) {
        return R.ok(service.maintainTeam(id, members));
    }

    @Operation(summary = "盘点人员分组")
    @GetMapping("/{id}/team")
    public R<List<EamCountTeamMember>> team(@PathVariable String id) {
        return R.ok(service.team(id));
    }

    @Operation(summary = "清单下发（BR-29 基准日快照冻结；SAMPLE 按分类风险抽样 BR-26）")
    @PostMapping("/{id}/issue")
    public R<Map<String, Object>> issue(@PathVariable String id) {
        return R.ok(service.issue(id));
    }

    @Operation(summary = "抽样预览（不落库：各分类比例与预计抽样数）")
    @GetMapping("/sample-preview")
    public R<Map<String, Object>> samplePreview() {
        return R.ok(service.samplePreview());
    }

    @Operation(summary = "盘点明细分页（快照+实盘+差异筛选）")
    @GetMapping("/{id}/lines")
    public R<Map<String, Object>> linePage(@PathVariable String id,
                                           @RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "50") long size,
                                           @RequestParam(required = false) String actualStatus,
                                           @RequestParam(required = false) String diffType,
                                           @RequestParam(required = false) String cardNo) {
        return R.ok(service.linePage(id, current, size, actualStatus, diffType, cardNo));
    }

    @Operation(summary = "单条实盘录入（扫码/手工/无标签待确认/盘盈/盘亏）")
    @PostMapping("/{id}/entry")
    public R<Map<String, Object>> countEntry(@PathVariable String id,
                                             @RequestParam(required = false) String cardNo,
                                             @RequestParam String actualStatus,
                                             @RequestParam(required = false) String actualNote,
                                             @RequestParam(defaultValue = "false") boolean refreshLastUsed,
                                             @RequestParam(required = false) String categoryCode) {
        return R.ok(service.countEntry(id, cardNo, actualStatus, actualNote, refreshLastUsed, categoryCode));
    }

    @Operation(summary = "CSV 批量导入实盘（行级错误清单不整批回滚）")
    @PostMapping(value = "/{id}/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public R<Map<String, Object>> batchImport(@PathVariable String id,
                                              @RequestBody String csvContent) {
        return R.ok(service.batchImport(id, csvContent));
    }

    @Operation(summary = "导出盘点清单 CSV（含 BOM）")
    @GetMapping(value = "/{id}/export", produces = "text/csv;charset=UTF-8")
    public String exportCsv(@PathVariable String id) {
        return service.exportCsv(id);
    }

    @Operation(summary = "覆盖率刷新")
    @PostMapping("/{id}/coverage")
    public R<Map<String, Object>> refreshCoverage(@PathVariable String id) {
        return R.ok(service.refreshCoverage(id));
    }

    @Operation(summary = "基准日后变动单独列示（BR-29）")
    @GetMapping("/{id}/post-base-changes")
    public R<Map<String, Object>> postBaseChanges(@PathVariable String id) {
        return R.ok(service.postBaseChanges(id));
    }

    @Operation(summary = "结案提交（BR-26 覆盖率 L1 卡控 → 报告生成 → 财务资产会计单签）")
    @PostMapping("/{id}/submit-report")
    public R<Map<String, Object>> submitReport(@PathVariable String id) {
        return R.ok(service.submitReport(id));
    }
}

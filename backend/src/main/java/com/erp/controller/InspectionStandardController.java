package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.entity.qms.SamplingPlan;
import com.erp.service.qms.InspectionStandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检验标准库（6.1 标准管理，/api/qms/standards）。
 * 写权限：SecurityConfig → ADMIN / QUALITY_ENG；发布审批签署走 /api/qms/approvals。
 */
@Tag(name = "检验标准库")
@RestController
@RequestMapping("/api/qms/standards")
public class InspectionStandardController {

    private final InspectionStandardService service;

    public InspectionStandardController(InspectionStandardService service) {
        this.service = service;
    }

    @Operation(summary = "标准分页")
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "新建标准（编码创建后不可改）")
    @PostMapping
    public R<?> create(@RequestBody Map<String, Object> body) {
        com.erp.entity.qms.InspectionStandard s = new com.erp.entity.qms.InspectionStandard();
        s.setStandardCode(str(body.get("standardCode")));
        s.setName(str(body.get("name")));
        s.setScopeType(str(body.get("scopeType")));
        s.setRiskLevel(str(body.get("riskLevel")));
        s.setDescription(str(body.get("description")));
        s.setOwnerId(str(body.get("ownerId")));
        s.setOwnerName(str(body.get("ownerName")));
        return R.ok(service.create(s));
    }

    @Operation(summary = "编辑标准基础信息（编码不可改）")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        com.erp.entity.qms.InspectionStandard s = new com.erp.entity.qms.InspectionStandard();
        s.setStandardCode(str(body.get("standardCode")));
        s.setName(str(body.get("name")));
        s.setScopeType(str(body.get("scopeType")));
        s.setRiskLevel(str(body.get("riskLevel")));
        s.setDescription(str(body.get("description")));
        s.setOwnerId(str(body.get("ownerId")));
        s.setOwnerName(str(body.get("ownerName")));
        return R.ok(service.update(id, s));
    }

    @Operation(summary = "标准详情（含版本列表）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "标准退役（停用不删，仅阻止新任务）")
    @PostMapping("/{id}/retire")
    public R<?> retire(@PathVariable String id) {
        return R.ok(service.retire(id));
    }

    @Operation(summary = "新建草稿版本（含特性与适用范围）")
    @PostMapping("/{id}/versions")
    public R<Map<String, Object>> createVersion(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.createVersion(id, body));
    }

    @Operation(summary = "更新草稿版本（已发布版本只读 422）")
    @PutMapping("/versions/{versionId}")
    public R<Map<String, Object>> updateVersion(@PathVariable String versionId, @RequestBody Map<String, Object> body) {
        return R.ok(service.updateVersion(versionId, body));
    }

    @Operation(summary = "提交发布（结构校验 + 区间冲突 → 审批，CTQ 双签）")
    @PostMapping("/versions/{versionId}/publish")
    public R<Map<String, Object>> publish(@PathVariable String versionId) {
        return R.ok(service.submitPublish(versionId));
    }

    @Operation(summary = "版本检验特性")
    @GetMapping("/versions/{versionId}/characteristics")
    public R<List<Map<String, Object>>> characteristics(@PathVariable String versionId) {
        return R.ok(service.characteristics(versionId));
    }

    @Operation(summary = "适用范围命中解析（任务生成快照用）")
    @GetMapping("/resolve")
    public R<Map<String, Object>> resolve(@RequestParam(required = false) String materialCode,
                                          @RequestParam(required = false) String categoryCode,
                                          @RequestParam(required = false) String supplierId,
                                          @RequestParam(required = false) String customerId,
                                          @RequestParam(required = false) String processId) {
        return R.ok(service.resolveFor(materialCode, categoryCode, supplierId, customerId, processId));
    }

    @Operation(summary = "抽样方案列表（6.1.4）")
    @GetMapping("/sampling-plans")
    public R<List<SamplingPlan>> samplingPlans() {
        return R.ok(service.samplingPlans());
    }

    @Operation(summary = "保存抽样方案（编码唯一）")
    @PostMapping("/sampling-plans")
    public R<SamplingPlan> saveSamplingPlan(@RequestBody SamplingPlan plan) {
        return R.ok(service.saveSamplingPlan(plan));
    }

    private String str(Object o) {
        if (o == null) {
            return null;
        }
        String v = String.valueOf(o);
        return v.isBlank() ? null : v;
    }
}

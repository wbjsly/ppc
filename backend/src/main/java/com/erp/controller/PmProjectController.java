package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 项目立项（project-initiation，11.3.1）。 */
@Tag(name = "项目立项")
@RestController
@RequestMapping("/api/pm/projects")
@RequiredArgsConstructor
public class PmProjectController {

    private final PmProjectService projectService;

    @GetMapping
    @Operation(summary = "项目列表（PM 仅自己担任项目经理的项目）")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projType,
            @RequestParam(required = false) String keyword) {
        return R.ok(projectService.list(status, projType, keyword));
    }

    @PostMapping
    @Operation(summary = "创建项目（DRAFT；EXT→SIGNED 合同门槛 / INTERNAL→单号必填）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(toMap(projectService.create(payload)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情（含 WBS 概览与预算行数）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(projectService.detail(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新项目（仅 DRAFT）")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(toMap(projectService.update(id, payload)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目（仅 DRAFT，级联 WBS/预算行）")
    public R<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        return R.ok(null);
    }

    private static Map<String, Object> toMap(com.erp.entity.pm.PmProject p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("projectNo", p.getProjectNo());
        m.put("name", p.getName());
        m.put("projType", p.getProjType());
        m.put("deliveryPath", p.getDeliveryPath());
        m.put("contractId", p.getContractId());
        m.put("contractNo", p.getContractNo());
        m.put("internalFormNo", p.getInternalFormNo());
        m.put("pmUserId", p.getPmUserId());
        m.put("pmName", p.getPmName());
        m.put("startDate", p.getStartDate());
        m.put("endDate", p.getEndDate());
        m.put("status", p.getStatus());
        m.put("baselineLocked", p.getBaselineLocked());
        m.put("baselineAmount", p.getBaselineAmount());
        return m;
    }
}

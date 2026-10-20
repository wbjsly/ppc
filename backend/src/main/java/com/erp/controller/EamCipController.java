package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamCipCost;
import com.erp.entity.eam.EamCipProject;
import com.erp.service.eam.EamCipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 在建工程（CIP）台账（add-asset-capitalization，spec asset-capitalization）。
 * 项目 + 成本归集明细；完工验收后发起在建转资。
 */
@Tag(name = "在建工程台账")
@RestController
@RequestMapping("/api/eam/cip")
public class EamCipController {

    private final EamCipService service;

    public EamCipController(EamCipService service) {
        this.service = service;
    }

    @Operation(summary = "项目分页（状态/关键字）")
    @GetMapping("/projects")
    public R<Page<EamCipProject>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "新建项目（编号自动生成）")
    @PostMapping("/projects")
    public R<EamCipProject> createProject(@RequestBody EamCipProject project) {
        return R.ok(service.createProject(project));
    }

    @Operation(summary = "修改项目（仅在建中）")
    @PutMapping("/projects/{id}")
    public R<EamCipProject> updateProject(@PathVariable String id,
                                          @RequestBody EamCipProject project) {
        project.setId(id);
        return R.ok(service.updateProject(project));
    }

    @Operation(summary = "归集明细录入（类别三选一）")
    @PostMapping("/costs")
    public R<EamCipCost> addCost(@RequestBody EamCipCost cost) {
        return R.ok(service.addCost(cost));
    }

    @Operation(summary = "项目详情（项目+明细+按类别合计）")
    @GetMapping("/projects/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "完工验收（置 COMPLETED + 验收日期，超期预警基准）")
    @PostMapping("/projects/{id}/complete")
    public R<EamCipProject> complete(@PathVariable String id,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(service.complete(id, date));
    }

    @Operation(summary = "归集合计（按类别）")
    @GetMapping("/projects/{id}/costs/summary")
    public R<List<Map<String, Object>>> costSummary(@PathVariable String id) {
        return R.ok(service.costSummary(id));
    }
}

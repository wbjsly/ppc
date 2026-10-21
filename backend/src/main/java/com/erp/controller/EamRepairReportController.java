package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamRepairReport;
import com.erp.service.eam.EamRepairReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 报修单（add-asset-maintenance，spec asset-maintenance FR-4.13-3-7/8）。
 * 提交报修对全员登录用户开放（design D1 角色口径）。
 */
@Tag(name = "资产运维-报修")
@RestController
@RequestMapping("/api/eam/repair-reports")
public class EamRepairReportController {

    private final EamRepairReportService service;

    public EamRepairReportController(EamRepairReportService service) {
        this.service = service;
    }

    @Operation(summary = "分页（卡片号/状态）")
    @GetMapping
    public R<Page<EamRepairReport>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String cardNo,
                                         @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, cardNo, status));
    }

    @Operation(summary = "提交报修（安全风险自动挂锁）")
    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody EamRepairReport report) {
        return R.ok(service.submit(report));
    }

    @Operation(summary = "确认转维修工单（BR-16/BR-19 双校验）")
    @PostMapping("/{id}/convert")
    public R<Map<String, Object>> convert(@PathVariable String id,
                                          @RequestParam String assignee,
                                          @RequestParam String resolvePlan,
                                          @RequestParam(required = false) String outsourceVendor) {
        return R.ok(service.convertToWorkOrder(id, assignee, resolvePlan, outsourceVendor));
    }

    @Operation(summary = "驳回报修")
    @PostMapping("/{id}/reject")
    public R<Map<String, Object>> reject(@PathVariable String id, @RequestParam String reason) {
        return R.ok(service.reject(id, reason));
    }
}

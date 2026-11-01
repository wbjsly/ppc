package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.MdmVersionCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 1.7 通用版本中心（版本对比/历史回溯/版本回滚）。
 */
@Tag(name = "MDM 版本中心")
@RestController
@RequestMapping("/api/mdm/version-center")
public class MdmVersionCenterController {

    private final MdmVersionCenterService service;

    public MdmVersionCenterController(MdmVersionCenterService service) {
        this.service = service;
    }

    @Operation(summary = "实体选择器（13 类主数据）")
    @GetMapping("/{bizType}/entities")
    public R<java.util.List<Map<String, Object>>> entities(
            @PathVariable String bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.entities(bizType, keyword, page, size));
    }

    @Operation(summary = "版本列表（1.7.2 历史回溯）")
    @GetMapping("/{bizType}/{entityId}/versions")
    public R<java.util.List<Map<String, Object>>> versions(
            @PathVariable String bizType, @PathVariable String entityId) {
        return R.ok(service.versions(bizType, entityId));
    }

    @Operation(summary = "版本快照查看")
    @GetMapping("/{bizType}/{entityId}/versions/{versionNo}")
    public R<Map<String, Object>> snapshot(
            @PathVariable String bizType, @PathVariable String entityId,
            @PathVariable int versionNo) {
        return R.ok(service.snapshot(bizType, entityId, versionNo));
    }

    @Operation(summary = "两版本逐字段对比（1.7.1）")
    @GetMapping("/{bizType}/{entityId}/diff")
    public R<Map<String, Object>> diff(
            @PathVariable String bizType, @PathVariable String entityId,
            @RequestParam int from, @RequestParam int to) {
        return R.ok(service.diff(bizType, entityId, from, to));
    }

    @Operation(summary = "一键回滚生成新版本（1.7.3，ADMIN）")
    @PostMapping("/{bizType}/{entityId}/rollback")
    public R<Map<String, Object>> rollback(
            @PathVariable String bizType, @PathVariable String entityId,
            @RequestParam int sourceVersionNo, @RequestParam String reason) {
        return R.ok(service.rollback(bizType, entityId, sourceVersionNo, reason));
    }
}

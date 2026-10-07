package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.vmi.MaterialIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 领料出库（4.5.2 + 2.8.1 寄售领用，spec material-issue）。
 * 创建/过账/作废限 ADMIN/WAREHOUSE；查询放行 ADMIN/WAREHOUSE/PM（SecurityConfig + 服务层二次校验）。
 */
@Tag(name = "领料出库")
@RestController
@RequestMapping("/api/inv/issues")
public class MaterialIssueController {

    private final MaterialIssueService service;

    public MaterialIssueController(MaterialIssueService service) {
        this.service = service;
    }

    @Operation(summary = "领料单分页（类型/状态/工单号/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String issueType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String workOrderNo,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, issueType, status, workOrderNo, keyword));
    }

    @Operation(summary = "领料单详情（头 + 配批行）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "FIFO 配批预检（创建对话框预览，不落库）")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> payload) {
        return R.ok(service.preview(payload));
    }

    @Operation(summary = "创建领料单（DRAFT，FIFO 配批固化；限 ADMIN/WAREHOUSE）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "过账（单事务扣减/物权转移；限 ADMIN/WAREHOUSE）")
    @PostMapping("/{id}/postings")
    public R<Map<String, Object>> post(@PathVariable String id) {
        return R.ok(service.post(id));
    }

    @Operation(summary = "作废（仅 DRAFT，原因必填；限 ADMIN/WAREHOUSE）")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable String id,
                          @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        service.cancel(id, reason == null ? null : String.valueOf(reason));
        return R.ok(null);
    }
}

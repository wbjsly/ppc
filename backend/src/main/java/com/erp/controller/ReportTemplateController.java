package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.ReportTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * 报表模板管理接口（add-report-template-console，菜单 14.1 四叶，
 * spec report-template-management）。写操作 service 层 BI_DESIGNER 校验（403 兜底）；
 * 渲染 = PUBLISHED 任意登录角色 / 非 PUBLISHED 仅设计师。
 */
@Tag(name = "报表模板管理")
@RestController
@RequestMapping("/api/bi/report-templates")
public class ReportTemplateController {

    private final ReportTemplateService service;

    public ReportTemplateController(ReportTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "模板列表", description = "status/category/keyword 过滤；非设计师仅见 PUBLISHED")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.list(status, category, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "模板详情（含生效版本与最新测试报告）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @Operation(summary = "新建模板", description = "SQL SELECT-only 白名单 + 口径 diffCheck 硬阻断（C-4.10-01）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改模板", description = "元数据变更 → 既有测试报告作废（强制重测）")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.update(id, payload));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模板（PUBLISHED/审批中 422 拒）")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "执行测试", description = "三项报告：样本/性能阈值/权限行数对比")
    public R<Map<String, Object>> runTest(@PathVariable String id) {
        return R.ok(service.runTest(id));
    }

    @PostMapping("/tests/{testId}/confirm-accuracy")
    @Operation(summary = "样本人工核对确认（三项合取刷新总体 PASS/FAIL）")
    public R<Map<String, Object>> confirmAccuracy(@PathVariable String testId) {
        return R.ok(service.confirmAccuracy(testId));
    }

    @GetMapping("/{id}/tests")
    @Operation(summary = "模板测试报告列表")
    public R<List<Map<String, Object>>> listTests(@PathVariable String id) {
        return R.ok(service.listTests(id));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交发布审批", description = "前置：有效 PASS 测试报告 + 指标全部注册（BR-4.10-09）；三方串行链")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "版本列表（版本号/口径版本/回退溯源）")
    public R<List<Map<String, Object>>> listVersions(@PathVariable String id) {
        return R.ok(service.listVersions(id));
    }

    @PostMapping("/versions/{versionId}/rollback")
    @Operation(summary = "回退到历史版本", description = "生成新版本号保留链（BR-4.10-07）")
    public R<Map<String, Object>> rollback(@PathVariable String versionId) {
        return R.ok(service.rollback(versionId));
    }

    @GetMapping("/{id}/render")
    @Operation(summary = "渲染预览", description = "分页表格数据；行级注入 + 敏感列掩码；状态两极权限")
    public R<Map<String, Object>> render(@PathVariable String id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "100") int size) {
        return R.ok(service.render(id, page, size));
    }
}

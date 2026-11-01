package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.AdhocQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 即席查询 API（add-adhoc-query-console，spec bi-adhoc-query，菜单 14.6.1/14.6.2）。
 * 查询/我的查询 = 登录即可（安全边界在查询层治理）；governance = BI_DESIGNER/ADMIN。
 */
@Tag(name = "BI-即席查询")
@RestController
@RequestMapping("/api/bi/adhoc")
@RequiredArgsConstructor
public class AdhocController {

    private final AdhocQueryService service;

    @Operation(summary = "数据集列表（PUBLISHED+行级可达，含字段语义树）")
    @GetMapping("/datasets")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> datasets() {
        return R.ok(service.datasets());
    }

    @Operation(summary = "发起查询（同步执行或入队，BR-4.10-30/31/33）")
    @PostMapping("/query")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> query(@RequestBody Map<String, Object> payload) {
        return R.ok(service.query(String.valueOf(payload.get("templateId")),
                String.valueOf(payload.get("queryJson"))));
    }

    @Operation(summary = "我的任务（含预检阻断 REJECTED 留痕）")
    @GetMapping("/tasks")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> myTasks() {
        return R.ok(service.myTasks());
    }

    @Operation(summary = "任务详情+结果集分页")
    @GetMapping("/tasks/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> taskDetail(@PathVariable String id,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return R.ok(service.taskDetail(id, page, size));
    }

    @Operation(summary = "用量治理（配额余量+按用户汇总，14.6.2）")
    @GetMapping("/governance")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> governance() {
        return R.ok(service.governance());
    }

    @Operation(summary = "我的查询列表")
    @GetMapping("/saved-queries")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> savedQueries() {
        return R.ok(service.savedQueries());
    }

    @Operation(summary = "保存我的查询")
    @PostMapping("/saved-queries")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> saveQuery(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveQuery(payload));
    }

    @Operation(summary = "删除我的查询")
    @DeleteMapping("/saved-queries/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Void> deleteSavedQuery(@PathVariable String id) {
        service.deleteSavedQuery(id);
        return R.ok();
    }
}

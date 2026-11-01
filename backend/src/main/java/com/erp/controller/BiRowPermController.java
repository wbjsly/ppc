package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.RowPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 行级权限管理台 API（add-perm-control-console，spec bi-row-permission，菜单 14.4.1）。 */
@Tag(name = "BI-行级权限")
@RestController
@RequestMapping("/api/bi/row-perms")
@RequiredArgsConstructor
public class BiRowPermController {

    private final RowPermissionService service;
    private final JdbcTemplate jdbc;

    @Operation(summary = "规则列表")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String userName,
                                             @RequestParam(required = false) String roleCode,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(userName, roleCode, status));
    }

    @Operation(summary = "新建草稿")
    @PostMapping
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "修改草稿")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.update(id, payload));
    }

    @Operation(summary = "提交审批（ROLE_DATA_GOV 单签）")
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "停用")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(service.disable(id));
    }

    @Operation(summary = "版本历史（快照）")
    @GetMapping("/{id}/versions")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @Operation(summary = "生效影响预览（并集范围 + 生效规则来源，BR-4.10-16）")
    @GetMapping("/impact-preview")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> impactPreview(@RequestParam String userName) {
        return R.ok(service.impactPreview(userName));
    }

    @Operation(summary = "权限校验审计日志（FR-4.10-3-5：QUERY 逐次留痕，管理视角）")
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<List<Map<String, Object>>> audit(
            @RequestParam(required = false) String userName,
            @RequestParam(defaultValue = "50") int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        if (userName != null && !userName.trim().isEmpty()) {
            return R.ok(jdbc.queryForList(
                    "SELECT USER_NAME AS userName, API_PATH AS apiPath, PARAMS_JSON AS paramsJson, "
                            + "MASK_COLS AS maskCols, ROW_COUNT AS rowCount, CREATE_DATE AS createDate "
                            + "FROM erp_bi_export_task WHERE TASK_TYPE = 'QUERY' AND USER_NAME = ? "
                            + "ORDER BY CREATE_DATE DESC LIMIT " + limit, userName));
        }
        return R.ok(jdbc.queryForList(
                "SELECT USER_NAME AS userName, API_PATH AS apiPath, PARAMS_JSON AS paramsJson, "
                        + "MASK_COLS AS maskCols, ROW_COUNT AS rowCount, CREATE_DATE AS createDate "
                        + "FROM erp_bi_export_task WHERE TASK_TYPE = 'QUERY' "
                        + "ORDER BY CREATE_DATE DESC LIMIT " + limit));
    }
}

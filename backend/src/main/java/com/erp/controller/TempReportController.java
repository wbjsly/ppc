package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.TempReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 临时报表 API（add-adhoc-query-console，spec bi-temp-report，菜单 14.6.3）。
 * 登录即可；写操作服务层校验属主（仅创建人可改/转正）。
 */
@Tag(name = "BI-临时报表")
@RestController
@RequestMapping("/api/bi/temp-reports")
@RequiredArgsConstructor
public class TempReportController {

    private final TempReportService service;

    @Operation(summary = "清单（我的+共享给我的，含剩余天数/已转正标记）")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    @Operation(summary = "另存（查询定义快照+30 天到期）")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> saveAs(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveAs(payload));
    }

    @Operation(summary = "打开（按访问者身份重跑，权限掩码实时生效）")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> open(@PathVariable String id,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return R.ok(service.open(id, page, size));
    }

    @Operation(summary = "共享设置（仅创建人）")
    @PostMapping("/{id}/share")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> share(@PathVariable String id,
                                        @RequestBody List<Map<String, Object>> shareList) {
        return R.ok(service.share(id, shareList));
    }

    @Operation(summary = "转正式报表（口径校验 C-4.10-01，BR-4.10-34）")
    @PostMapping("/{id}/promote")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> promote(@PathVariable String id) {
        return R.ok(service.promote(id));
    }
}

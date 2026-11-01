package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.KpiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 经营看板 API（add-kpi-dashboard，spec bi-kpi-dashboard，菜单 14.7.1~3）。
 * 读=登录即可（数据范围行级注入）；定义/目标写+手动快照=BI_DESIGNER/ADMIN。
 */
@Tag(name = "BI-经营看板")
@RestController
@RequestMapping("/api/bi/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiService service;

    @Operation(summary = "达成概览（KPI 卡片三色，14.7.1）")
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> summary() {
        return R.ok(service.summary());
    }

    @Operation(summary = "趋势序列（近 N 日快照+目标线，14.7.2）")
    @GetMapping("/trend")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> trend(@RequestParam String kpiKey,
                                        @RequestParam(required = false) String dimValue,
                                        @RequestParam(defaultValue = "0") int days) {
        return R.ok(service.trend(kpiKey, dimValue, days));
    }

    @Operation(summary = "预警列表（ACTIVE/RECOVERED，14.7.3）")
    @GetMapping("/alerts")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> alerts(@RequestParam(required = false) String status) {
        return R.ok(service.alerts(status));
    }

    @Operation(summary = "KPI 定义列表（配置抽屉）")
    @GetMapping("/defs")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> listDefs() {
        return R.ok(service.listDefs());
    }

    @Operation(summary = "保存 KPI 定义（口径一致 C-4.10-01 + SQL SELECT-only）")
    @PostMapping("/defs")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> saveDef(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveDef(payload));
    }

    @PutMapping("/defs/{id}")
    @Operation(summary = "更新 KPI 定义")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> updateDef(@PathVariable String id,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateDef(id, payload));
    }

    @Operation(summary = "删除 KPI 定义")
    @DeleteMapping("/defs/{id}")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Void> deleteDef(@PathVariable String id) {
        service.deleteDef(id);
        return R.ok();
    }

    @Operation(summary = "期间目标列表")
    @GetMapping("/targets")
    @PreAuthorize("isAuthenticated()")
    public R<List<Map<String, Object>>> listTargets(@RequestParam String kpiKey) {
        return R.ok(service.listTargets(kpiKey));
    }

    @Operation(summary = "保存期间目标（KPI+期间 upsert）")
    @PostMapping("/targets")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> saveTarget(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveTarget(payload));
    }

    @Operation(summary = "手动触发快照+预警扫描（BI_DESIGNER/ADMIN，冒烟/补数用）")
    @PostMapping("/snapshot/run")
    @PreAuthorize("hasAnyRole('BI_DESIGNER','ADMIN')")
    public R<Map<String, Object>> runSnapshot() {
        return R.ok(service.runSnapshot());
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmBaselineVersionService;
import com.erp.service.pm.PmProjectChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
 * 项目变更管理（add-project-change，M11.7；11.7.1 变更申请 / 11.7.2 变更审批）。
 * 五类变更申请 + 影响分析 + 三级审批（统一底座）+ 基线版本化 + 执行跟踪 + 合同联动。
 * 菜单权限：11.7.1=PM/PMO/ADMIN，11.7.2=PMO/FINANCE/EXEC/ADMIN；单据级角色校验在服务层。
 */
@Tag(name = "项目变更管理")
@RestController
@RequestMapping("/api/pm/changes")
@RequiredArgsConstructor
public class PmProjectChangeController {

    private final PmProjectChangeService service;
    private final PmBaselineVersionService versionService;

    @GetMapping
    @Operation(summary = "变更单清单（按项目/状态/类型筛选）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String projectId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String changeType) {
        return R.ok(service.list(projectId, status, changeType));
    }

    @GetMapping("/{id}")
    @Operation(summary = "变更单详情（含受影响 WBS 集）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/draft")
    @Operation(summary = "保存草稿（WBS 关联可暂缺；倒签 L1；仅 ACTIVE 项目）")
    public R<Map<String, Object>> draft(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, false));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交审批（WBS 必填 L1；服务端算预算占比并三级路由；第三档校验客户书面附件）")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, true));
    }

    @PutMapping("/{id}/advance")
    @Operation(summary = "执行推进：执行中 → 已完成（仅变更发起人/PM）")
    public R<Map<String, Object>> advance(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.advance(id, payload));
    }

    @PutMapping("/{id}/verify")
    @Operation(summary = "PMO 核验：已完成 → 已验证（偏差>50% L4 升级通知 PMO）")
    public R<Map<String, Object>> verify(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.verify(id, payload));
    }

    @GetMapping("/stats")
    @Operation(summary = "变更台账统计（按类型/原因/状态分组计数）")
    public R<Map<String, Object>> stats(@RequestParam(required = false) String projectId) {
        return R.ok(service.stats(projectId));
    }

    // ---- 基线版本（BR-4.8-31 只读） ----

    @GetMapping("/{projectId}/baseline-versions")
    @Operation(summary = "项目全部基线版本（版本号倒序，含当前生效标记）")
    public R<List<Map<String, Object>>> baselineVersions(@PathVariable String projectId) {
        return R.ok(versionService.list(projectId));
    }

    @GetMapping("/{projectId}/baseline-versions/{verNoLabel}")
    @Operation(summary = "单版本全量预算快照（只读，历史版永久可查）")
    public R<Map<String, Object>> baselineSnapshot(@PathVariable String projectId,
                                                   @PathVariable String verNoLabel) {
        return R.ok(versionService.snapshot(projectId, verNoLabel));
    }

    @GetMapping("/{projectId}/baseline-versions/current")
    @Operation(summary = "当前生效基线版本")
    public R<Map<String, Object>> baselineCurrent(@PathVariable String projectId) {
        return R.ok(versionService.current(projectId));
    }
}

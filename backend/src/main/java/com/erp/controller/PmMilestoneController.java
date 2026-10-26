package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmMilestoneService;
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
 * 里程碑管理（add-milestone-management，design D6；11.4.1~3）。
 * 审批走通用底座 /api/qms/approvals/{todo,pass,reject}（BIZ_TYPE=PmMilestone）；
 * 交付物上传走既有 /api/common/attachments（BIZ_TYPE=MILESTONE_DELIV）。
 */
@Tag(name = "项目里程碑")
@RestController
@RequestMapping("/api/pm/milestones")
@RequiredArgsConstructor
public class PmMilestoneController {

    private final PmMilestoneService milestoneService;

    @GetMapping
    @Operation(summary = "里程碑列表（projectId 过滤；PM 裁剪；财务只读放行在 SecurityConfig GET 前缀）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String projectId) {
        return R.ok(milestoneService.list(projectId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PM','PMO','ADMIN')")
    @Operation(summary = "创建（ACTIVE 项目 + WBS 阶段节点校验）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(milestoneService.create(payload));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PM','PMO','ADMIN')")
    @Operation(summary = "编辑（仅 PLANNED/REWORK；依赖成环校验）")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(milestoneService.update(id, payload));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PM','PMO','ADMIN')")
    @Operation(summary = "删除（仅 PLANNED 且无附件无评审无依赖）")
    public R<Void> delete(@PathVariable String id) {
        milestoneService.delete(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('PM','PMO','ADMIN')")
    @Operation(summary = "发起评审（两道 L1：交付物≥1 + 前序 DONE）")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(milestoneService.submit(id));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "三态评审（PMO；PASS 自动发起 L2 审批）")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(milestoneService.review(id, payload));
    }

    @GetMapping("/{id}/deliverables")
    @Operation(summary = "交付物版本列表（附件派生，历史版本只读）")
    public R<List<Map<String, Object>>> deliverables(@PathVariable String id) {
        return R.ok(milestoneService.deliverables(id));
    }

    @PostMapping("/{id}/remind")
    @PreAuthorize("hasAnyRole('PM','PMO','ADMIN')")
    @Operation(summary = "手工催办（站内通知 PMO）")
    public R<Map<String, Object>> remind(@PathVariable String id) {
        return R.ok(milestoneService.remind(id));
    }

    @GetMapping("/progress")
    @Operation(summary = "进度看板（完成率+逐里程碑偏差工作日；PM 裁剪，财务只读）")
    public R<Map<String, Object>> progress(@RequestParam(required = false) String projectId) {
        return R.ok(milestoneService.progress(projectId));
    }
}

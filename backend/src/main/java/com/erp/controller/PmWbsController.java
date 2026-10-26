package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmTemplateService;
import com.erp.service.pm.PmWbsService;
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

/** WBS 分解与模板（project-initiation，11.3.2）。 */
@Tag(name = "项目WBS")
@RestController
@RequestMapping("/api/pm")
@RequiredArgsConstructor
public class PmWbsController {

    private final PmWbsService wbsService;
    private final PmTemplateService templateService;

    @GetMapping("/wbs")
    @Operation(summary = "WBS 树查询")
    public R<List<Map<String, Object>>> tree(@RequestParam String projectId) {
        return R.ok(wbsService.tree(projectId));
    }

    @PostMapping("/wbs")
    @Operation(summary = "新增节点（层级规则校验；任务级级联预算行）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(wbsService.create(payload));
    }

    @PutMapping("/wbs/{id}")
    @Operation(summary = "更新节点（层级/父节点不可改；工作包交付物必填）")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(wbsService.update(id, payload));
    }

    @DeleteMapping("/wbs/{id}")
    @Operation(summary = "删除节点（级联子节点与预算行）")
    public R<Void> delete(@PathVariable String id) {
        wbsService.delete(id);
        return R.ok(null);
    }

    @GetMapping("/wbs/validate")
    @Operation(summary = "立项校验（WBS 非空 + 每分支至任务级），返回错误清单")
    public R<List<String>> validate(@RequestParam String projectId) {
        return R.ok(wbsService.validateForBaseline(projectId));
    }

    // ---------- 模板（PMO/ADMIN 维护；PM 只读选用） ----------

    @GetMapping("/templates")
    @Operation(summary = "模板清单（status=1 仅启用）")
    public R<List<Map<String, Object>>> templateList(@RequestParam(required = false) String status) {
        return R.ok(templateService.list(status));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "新建模板（PMO/ADMIN）")
    public R<Map<String, Object>> createTemplate(@RequestBody Map<String, Object> payload) {
        return R.ok(templateService.create(payload));
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "更新模板（PMO/ADMIN，含 JSON 校验）")
    public R<Map<String, Object>> updateTemplate(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(templateService.update(id, payload));
    }

    @PutMapping("/templates/{id}/status")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "启停模板（PMO/ADMIN）")
    public R<Void> toggleTemplate(@PathVariable String id, @RequestParam String status) {
        templateService.toggle(id, status);
        return R.ok(null);
    }

    @DeleteMapping("/templates/{id}")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "删除模板（PMO/ADMIN）")
    public R<Void> deleteTemplate(@PathVariable String id) {
        templateService.delete(id);
        return R.ok(null);
    }

    @PostMapping("/templates/generate")
    @Operation(summary = "一键生成 WBS 骨架（已有 WBS 须 confirm=true）")
    public R<Map<String, Object>> generate(@RequestParam String projectId,
                                           @RequestParam String templateId,
                                           @RequestParam(required = false, defaultValue = "false") boolean confirm) {
        return R.ok(templateService.generate(projectId, templateId, confirm));
    }
}

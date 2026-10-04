package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmItemCategory;
import com.erp.entity.mdm.MdmItemCategoryVersion;
import com.erp.service.mdm.MdmItemCategoryService;
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

/** 物料分类维护（1.2.3）：树形 CRUD/停用/合并/迁移 + 版本审计 */
@RestController
@RequestMapping("/api/mdm/item-categories")
public class MdmItemCategoryController {

    private final MdmItemCategoryService service;

    public MdmItemCategoryController(MdmItemCategoryService service) {
        this.service = service;
    }

    /** 管理页树形（全部状态）；平铺只读接口由 /api/mdm/item-categories 提供（物料表单消费） */
    @GetMapping("/tree")
    public R<List<MdmItemCategory>> tree() {
        return R.ok(service.tree());
    }

    @PostMapping
    public R<MdmItemCategory> create(@RequestBody MdmItemCategory category) {
        return R.ok(service.create(category));
    }

    @PutMapping
    public R<MdmItemCategory> update(@RequestBody MdmItemCategory category) {
        return R.ok(service.update(category));
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }

    /** 合并影响面（执行前提示：受影响物料/子分类/是否同前缀） */
    @GetMapping("/{id}/merge-impact")
    public R<Map<String, Object>> mergeImpact(@PathVariable String id,
                                              @RequestParam String targetId) {
        return R.ok(service.mergeImpact(id, targetId));
    }

    @PostMapping("/{id}/merge")
    public R<Void> merge(@PathVariable String id,
                         @RequestParam String targetId,
                         @RequestParam String reason,
                         @RequestParam(defaultValue = "false") boolean confirmLarge) {
        service.merge(id, targetId, reason, confirmLarge);
        return R.ok();
    }

    @PutMapping("/{id}/move")
    public R<Void> move(@PathVariable String id,
                        @RequestParam(required = false) String targetParentId,
                        @RequestParam String reason) {
        service.move(id, targetParentId, reason);
        return R.ok();
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmItemCategoryVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}

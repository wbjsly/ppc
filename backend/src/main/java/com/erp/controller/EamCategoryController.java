package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.service.eam.EamCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产分类主数据（add-asset-capitalization，spec asset-capitalization）。
 * 无审批立即生效；转资按编码带入默认折旧政策。
 */
@Tag(name = "资产分类")
@RestController
@RequestMapping("/api/eam/categories")
public class EamCategoryController {

    private final EamCategoryService service;

    public EamCategoryController(EamCategoryService service) {
        this.service = service;
    }

    @Operation(summary = "分页（关键字/状态）")
    @GetMapping
    public R<Page<EamAssetCategory>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "新增（无审批立即生效，编码唯一 409）")
    @PostMapping
    public R<EamAssetCategory> create(@RequestBody EamAssetCategory category) {
        return R.ok(service.create(category));
    }

    @Operation(summary = "修改（乐观锁，无审批立即生效）")
    @PutMapping("/{id}")
    public R<EamAssetCategory> update(@PathVariable String id,
                                      @RequestBody EamAssetCategory category) {
        category.setId(id);
        return R.ok(service.update(category));
    }

    @Operation(summary = "按编码取生效分类（转资带入默认折旧政策）")
    @GetMapping("/active/{code}")
    public R<EamAssetCategory> active(@PathVariable String code) {
        return R.ok(service.getActive(code));
    }
}

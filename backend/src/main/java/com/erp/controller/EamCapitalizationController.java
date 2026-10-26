package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamCapitalization;
import com.erp.service.eam.EamCapitalizationService;
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

import java.util.List;
import java.util.Map;

/**
 * 资产转资（add-asset-capitalization，spec asset-capitalization FR-4.13-1-1~7）。
 * 双模式申请 → 单签审核 → 一物一卡建卡 + 转资凭证。
 */
@Tag(name = "资产转资")
@RestController
@RequestMapping("/api/eam/capitalizations")
public class EamCapitalizationController {

    private final EamCapitalizationService service;

    public EamCapitalizationController(EamCapitalizationService service) {
        this.service = service;
    }

    @Operation(summary = "分页（模式/状态/关键字）")
    @GetMapping
    public R<Page<EamCapitalization>> page(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String mode,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, mode, status, keyword));
    }

    @Operation(summary = "创建转资申请（来源校验 + BR-08/09 + 年限预警 warnings）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody EamCapitalization cap) {
        return R.ok(service.create(cap));
    }

    @Operation(summary = "草稿编辑（仅 DRAFT）")
    @PutMapping("/{id}")
    public R<EamCapitalization> update(@PathVariable String id,
                                       @RequestBody EamCapitalization cap) {
        cap.setId(id);
        return R.ok(service.update(cap));
    }

    @Operation(summary = "详情（含分类/来源/折旧预览）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "提交审核（BR-07 原值一致性 + 科目前置预检 → 单签）")
    @PostMapping("/{id}/submit")
    public R<EamCapitalization> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "折旧预览（纯计算：残值/月折旧额）")
    @GetMapping("/{id}/depreciation-preview")
    public R<Map<String, Object>> preview(@PathVariable String id) {
        return R.ok(service.depreciationPreview(id));
    }

    @Operation(summary = "待转资清单（超期分级：>grace 预警 / >60 升级）")
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        return R.ok(service.pendingList());
    }
}

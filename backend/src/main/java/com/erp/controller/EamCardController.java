package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamAssetCard;
import com.erp.service.eam.EamCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 资产卡片台账（add-asset-capitalization，7.1.3 卡片生成）。
 * 列表/详情（含标签打印数据）/更新（BR-4.13-10 锁定拦截）。
 */
@Tag(name = "资产卡片")
@RestController
@RequestMapping("/api/eam/cards")
public class EamCardController {

    private final EamCardService service;

    public EamCardController(EamCardService service) {
        this.service = service;
    }

    @Operation(summary = "分页（状态/分类/关键字）")
    @GetMapping
    public R<Page<EamAssetCard>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, categoryCode, keyword));
    }

    @Operation(summary = "详情（含分类/转资单，供标签打印）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "更新（BR-4.13-10 锁定拦截）")
    @PutMapping("/{id}")
    public R<EamAssetCard> update(@PathVariable String id, @RequestBody EamAssetCard card) {
        card.setId(id);
        return R.ok(service.update(card));
    }
}

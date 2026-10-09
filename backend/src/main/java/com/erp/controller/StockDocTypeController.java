package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvDocType;
import com.erp.service.inv.StockDocTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 出入库业务类型配置（spec stock-doc-type，4.4.1 配套）。
 * 查询仅需认证；增改/启停限 ROLE_ADMIN（服务层强制）。
 */
@Tag(name = "出入库类型配置")
@RestController
@RequestMapping("/api/inv/doc-types")
public class StockDocTypeController {

    private final StockDocTypeService service;

    public StockDocTypeController(StockDocTypeService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "类型列表（含停用，携 ENABLED 标记）")
    public R<List<InvDocType>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "新增类型（ADMIN；TYPE_CODE 唯一且创建后锁定）")
    public R<InvDocType> create(@RequestBody InvDocType req) {
        return R.ok(service.create(req));
    }

    @PutMapping
    @Operation(summary = "修改类型（ADMIN；TYPE_CODE 不可改）")
    public R<InvDocType> update(@RequestBody InvDocType req) {
        return R.ok(service.update(req));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用类型（ADMIN）")
    public R<InvDocType> enable(@PathVariable String id) {
        return R.ok(service.setEnabled(id, true));
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "停用类型（ADMIN；停用后引擎拒新过账）")
    public R<InvDocType> disable(@PathVariable String id) {
        return R.ok(service.setEnabled(id, false));
    }
}

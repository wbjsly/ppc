package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvWarehouse;
import com.erp.service.inv.WarehouseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库档案（4.1.1，spec warehouse-master）。
 * 菜单 PERM = ROLE_WAREHOUSE,ROLE_ADMIN；写操作在服务层再校验角色（前端权限声明仅提示）。
 */
@RestController
@RequestMapping("/api/inv/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<InvWarehouse>> list(@RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String status) {
        return R.ok(service.list(keyword, status));
    }

    /** 下拉用：仅启用仓库（发货单与预留的可选范围） */
    @GetMapping("/enabled")
    public R<List<InvWarehouse>> enabled() {
        return R.ok(service.listEnabled());
    }

    @GetMapping("/{id}")
    public R<InvWarehouse> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    public R<InvWarehouse> create(@RequestBody InvWarehouse warehouse) {
        return R.ok(service.create(warehouse));
    }

    @PutMapping
    public R<InvWarehouse> update(@RequestBody InvWarehouse warehouse) {
        return R.ok(service.update(warehouse));
    }

    @PutMapping("/{id}/enable")
    public R<Void> enable(@PathVariable String id) {
        service.enable(id);
        return R.ok();
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }
}

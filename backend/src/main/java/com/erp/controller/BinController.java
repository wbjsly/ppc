package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvBin;
import com.erp.service.inv.BinService;
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
 * 仓库仓位（4.1.2，spec warehouse-zone-planning）。
 * 权限：ROLE_WAREHOUSE,ROLE_ADMIN（服务层二次校验，design D7）。
 */
@Tag(name = "仓库仓位")
@RestController
@RequestMapping("/api/inv/bins")
public class BinController {

    private final BinService service;

    public BinController(BinService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "仓位查询", description = "仓库/区域/编号关键字/状态筛选，编号升序，带区域名称与默认属性对照")
    public R<List<Map<String, Object>>> query(@RequestParam(required = false) String whCode,
                                              @RequestParam(required = false) String zoneCode,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return R.ok(service.query(whCode, zoneCode, keyword, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "仓位详情")
    public R<InvBin> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "单个新建", description = "编号服务端生成（请求体 binCode 忽略）；属性继承区域默认可覆盖；停用区域 422")
    public R<InvBin> create(@RequestBody InvBin bin) {
        return R.ok(service.create(bin));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量规划", description = "排/列/层区间 → 预检冲突 409 整体拒绝 → 单事务生成；上限 app.inv.bin-batch-max")
    public R<Map<String, Object>> batch(@RequestBody Map<String, Object> payload) {
        return R.ok(service.batchCreate(payload));
    }

    @PutMapping
    @Operation(summary = "变更仓位属性", description = "编号锁定（422）")
    public R<InvBin> update(@RequestBody InvBin bin) {
        return R.ok(service.update(bin));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用仓位", description = "区域须为启用状态")
    public R<Void> enable(@PathVariable String id) {
        service.enable(id);
        return R.ok();
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "停用仓位")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }
}

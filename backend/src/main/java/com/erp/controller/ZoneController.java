package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvZone;
import com.erp.service.inv.ZoneService;
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
 * 仓库区域（4.1.2，spec warehouse-zone-planning）。
 * 权限：ROLE_WAREHOUSE,ROLE_ADMIN（服务层二次校验，design D7）。
 */
@Tag(name = "仓库区域")
@RestController
@RequestMapping("/api/inv/zones")
public class ZoneController {

    private final ZoneService service;

    public ZoneController(ZoneService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "区域列表", description = "按仓库/关键字/状态筛选，排序+编码升序")
    public R<List<InvZone>> list(@RequestParam(required = false) String whCode,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String status) {
        return R.ok(service.list(whCode, keyword, status));
    }

    @GetMapping("/with-count")
    @Operation(summary = "区域+仓位数", description = "规划页左栏：区域及其下仓位计数")
    public R<List<Map<String, Object>>> withCount(@RequestParam(required = false) String whCode,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String status) {
        return R.ok(service.listWithBinCount(whCode, keyword, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "区域详情")
    public R<InvZone> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "新建区域", description = "编码仓库内唯一（409）、创建后不可改；停用仓库禁建（422）")
    public R<InvZone> create(@RequestBody InvZone zone) {
        return R.ok(service.create(zone));
    }

    @PutMapping
    @Operation(summary = "变更区域", description = "编码锁定（422）、乐观锁并发 409、写 N+1 版本快照")
    public R<InvZone> update(@RequestBody InvZone zone) {
        return R.ok(service.update(zone));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用区域")
    public R<Void> enable(@PathVariable String id) {
        service.enable(id);
        return R.ok();
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "停用区域", description = "不级联既有仓位")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }
}

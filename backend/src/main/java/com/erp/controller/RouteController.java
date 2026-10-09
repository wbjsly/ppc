package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvRoute;
import com.erp.service.inv.RouteService;
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
 * 配送线路主数据（4.8.1 页内 Tab，spec wave-management）：
 * 查询认证即可，写限 ADMIN/WAREHOUSE（服务层二次校验，design D7）。
 */
@Tag(name = "配送线路")
@RestController
@RequestMapping("/api/inv/routes")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询线路")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(keyword, status, current, size));
    }

    @GetMapping("/active")
    @Operation(summary = "启用线路全量（客户档案上拉选/聚类展示）")
    public R<List<InvRoute>> active() {
        return R.ok(service.listActive());
    }

    @PostMapping
    @Operation(summary = "新增线路（编码唯一 409）")
    public R<Map<String, Object>> create(@RequestBody InvRoute route) {
        return R.ok(service.create(route));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑线路（编码不可改）")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody InvRoute route) {
        return R.ok(service.update(id, route));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "停用/启用线路（C-0-05 退役语义）")
    public R<Map<String, Object>> status(@PathVariable String id,
                                         @RequestParam String status) {
        return R.ok(service.changeStatus(id, status));
    }
}

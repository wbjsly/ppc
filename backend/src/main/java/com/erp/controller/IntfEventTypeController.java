package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.intf.IntfEventType;
import com.erp.service.intf.EventTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 事件定义注册表（add-event-subscription，菜单 12.2.1）。
 * 读 INTF_OPS+（SecurityConfig 兜底）；写在服务层限 INTF_ARCH/ADMIN（403 兜底）。
 */
@Tag(name = "接口事件定义")
@RestController
@RequestMapping("/api/intf/event-types")
public class IntfEventTypeController {

    private final EventTypeService service;

    public IntfEventTypeController(EventTypeService service) {
        this.service = service;
    }

    @Operation(summary = "事件定义分页（类型码/状态）")
    @GetMapping
    public R<Page<IntfEventType>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String eventType,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, eventType, status));
    }

    @Operation(summary = "注册事件类型（命名 {对象}_{动作} 422；重复 409；限集成架构师/管理员）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @Operation(summary = "修改事件类型（Schema 变更未升 Major → 422）")
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.update(id, body));
    }

    @Operation(summary = "启停事件类型（停用阻断该类型新事件发布）")
    @PostMapping("/{id}/toggle")
    public R<Map<String, Object>> toggle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        return R.ok(service.toggle(id, status == null ? "" : String.valueOf(status)));
    }
}

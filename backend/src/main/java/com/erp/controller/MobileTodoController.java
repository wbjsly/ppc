package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotMobileTodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 移动待办中心（add-iot-mobile 13.7，FR-4.11-7-x / BR-4.11-42~47）。
 * 聚合审批流待办 + 30min 超时升级 + 处理回写原单。
 */
@Tag(name = "移动物联-移动待办")
@RestController
@RequestMapping("/api/iot/todos")
public class MobileTodoController {

    private final IotMobileTodoService service;

    public MobileTodoController(IotMobileTodoService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "13.7.4 待办聚合（本域 + 审批流，按超时倒计时排序）")
    public R<List<Map<String, Object>>> aggregate(
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String todoType) {
        List<Map<String, Object>> all = service.aggregate(assignee, status);
        if (todoType != null && !todoType.isBlank()) {
            all = all.stream().filter(t -> todoType.equals(String.valueOf(t.get("todoType"))))
                    .toList();
        }
        return R.ok(all);
    }

    @PostMapping
    @Operation(summary = "创建待办（异常/巡检/审批联动入口）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @PostMapping("/{id}/handle")
    @Operation(summary = "13.7 处理并回写原单（双因子已在签审口校验）")
    public R<Map<String, Object>> handle(@PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        return R.ok(service.handle(id, body.get("opinion"), body.get("result")));
    }

    @PostMapping("/scan-escalate")
    @Operation(summary = "30min 超时升级扫描（BR-4.11-44/47）")
    public R<Map<String, Object>> scanEscalate() {
        return R.ok(service.scanEscalate());
    }
}

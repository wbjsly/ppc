package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.InboundWorkbenchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 入库作业台查询（spec inbound-workbench，4.4.1~4.4.4 类型参数化；仅需认证）。
 * 过账/确认动作由前端直连既有 domain action（双入口同后端同权限）。
 */
@Tag(name = "入库作业台")
@RestController
@RequestMapping("/api/inv/workbench")
public class InboundWorkbenchController {

    private final InboundWorkbenchService service;

    public InboundWorkbenchController(InboundWorkbenchService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    @Operation(summary = "按类型取入库任务（PURCHASE_IN 收货单+检验状态 / RETURN_IN 退货镜像 / 未建域空态）")
    public R<Map<String, Object>> tasks(@RequestParam String type,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.tasks(type, keyword, status, current, size));
    }
}

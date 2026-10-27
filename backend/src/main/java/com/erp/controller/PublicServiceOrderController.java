package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmServiceOrderService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务工单客户确认（匿名免密链接端点，add-service-order design D6）。
 * SecurityConfig 白名单 /api/public/service-orders/**；token 一次性、过期即废；
 * 仅暴露工单摘要读取 + 确认/驳回两个动作，不回显敏感数据。
 */
@Hidden
@Tag(name = "服务工单客户确认（匿名）")
@RestController
@RequestMapping("/api/public/service-orders")
@RequiredArgsConstructor
public class PublicServiceOrderController {

    private final PmServiceOrderService service;

    @GetMapping("/confirm/{token}")
    @Operation(summary = "token 取工单摘要（确认页展示）")
    public R<Map<String, Object>> view(@PathVariable String token) {
        return R.ok(service.tokenView(token));
    }

    @PostMapping("/confirm/{token}")
    @Operation(summary = "客户确认/驳回（result=RESOLVED|REJECTED）")
    public R<Map<String, Object>> confirm(@PathVariable String token,
                                          @RequestBody Map<String, Object> payload) {
        return R.ok(service.tokenConfirm(token, payload));
    }
}

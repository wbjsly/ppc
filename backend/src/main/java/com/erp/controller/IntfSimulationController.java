package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.intf.IntfSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模拟调用方（spec open-api-gateway 2.8）：页面「压测/造数」按钮与测试套件共用。
 * 权限走 SecurityConfig `/api/intf/**` → ADMIN 或 INTF_OPS。
 */
@Tag(name = "接口模拟调用")
@RestController
@RequestMapping("/api/intf/simulations")
public class IntfSimulationController {

    private final IntfSimulationService service;

    public IntfSimulationController(IntfSimulationService service) {
        this.service = service;
    }

    @Operation(summary = "按模式发起模拟调用（OK / BAD_SIGNATURE / EXPIRED_TIMESTAMP / STORM / RATE_STORM）")
    @PostMapping
    public R<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        Object partner = body.get("partnerCode");
        Object mode = body.get("mode");
        if (partner == null || String.valueOf(partner).trim().isEmpty()) {
            throw new ServiceException(400, "partnerCode 不能为空");
        }
        String m = mode == null || String.valueOf(mode).trim().isEmpty()
                ? IntfSimulationService.MODE_OK : String.valueOf(mode).trim().toUpperCase();
        int count = 1;
        Object c = body.get("count");
        if (c != null) {
            try {
                count = Integer.parseInt(String.valueOf(c));
            } catch (NumberFormatException e) {
                throw new ServiceException(400, "count 必须是整数");
            }
        }
        try {
            return R.ok(service.run(String.valueOf(partner).trim(), m, count));
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, e.getMessage());
        }
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ops.OpsCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 18.4 容量管理：容量预警/扩容执行/结账保障开关。
 */
@Tag(name = "运维容量管理")
@RestController
@RequestMapping("/api/ops/capacity")
public class OpsCapacityController {

    private final OpsCapacityService service;
    private final JdbcTemplate jdbc;

    public OpsCapacityController(OpsCapacityService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @Operation(summary = "容量指标趋势（18.4.1）")
    @GetMapping("/metrics")
    public R<List<Map<String, Object>>> metrics(@RequestParam(defaultValue = "30") int days) {
        return R.ok(service.metrics(days));
    }

    @Operation(summary = "手动触发采集（18.4.2 扩容执行前评估）")
    @PostMapping("/collect")
    public R<Map<String, Object>> collect() {
        return R.ok(Map.of("collected", service.collect()));
    }

    @Operation(summary = "结账保障开关（18.4.3，联动 BR-5.8-14）")
    @GetMapping("/accounting-guard")
    public R<Map<String, Object>> guardStatus() {
        String v = "0";
        try {
            v = jdbc.queryForObject(
                    "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY='ACCOUNTING_GUARD_ENABLED' "
                            + "AND DEL_FLAG='0'", String.class);
        } catch (Exception ignored) {
            // 参数未配置默认关
        }
        return R.ok(Map.of("enabled", "1".equals(v)));
    }

    @Operation(summary = "结账保障开关切换（ADMIN）")
    @PostMapping("/accounting-guard")
    public R<Map<String, Object>> setGuard(@RequestParam boolean enabled) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_param WHERE PARAM_KEY='ACCOUNTING_GUARD_ENABLED'",
                Integer.class);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE=?, UPDATE_DATE=NOW() "
                    + "WHERE PARAM_KEY='ACCOUNTING_GUARD_ENABLED'", enabled ? "1" : "0");
        } else {
            jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, "
                            + "PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), 'ACCOUNTING_GUARD_ENABLED', ?, 'STRING', 'OPS', "
                            + "'结账保障模式开关', 'ops', NOW(), '0', 0)", enabled ? "1" : "0");
        }
        return R.ok(Map.of("enabled", enabled));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计日志检索（16.7.1，只读，限 ADMIN；无任何写端点 = BR-5.6-13 应用层约定，
 * 数据库层由触发器硬阻断）。
 */
@RestController
@RequestMapping("/api/system/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "组合检索（操作人/动作/对象/关键词/时间范围 + 分页 + 脱敏）")
    @GetMapping
    public R<Map<String, Object>> search(@RequestParam(required = false) String changeBy,
                                         @RequestParam(required = false) String fieldName,
                                         @RequestParam(required = false) String empId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return R.ok(auditLogService.search(changeBy, fieldName, empId, keyword, from, to, page, size));
    }

    @Operation(summary = "防篡改尝试记录列表（BR-5.6-13 证据面）")
    @GetMapping("/tamper")
    public R<List<Map<String, Object>>> tamper(@RequestParam(required = false) Integer limit) {
        return R.ok(auditLogService.tamperList(limit));
    }

    @Operation(summary = "尝试行告警扫描（未通知尝试行 → 站内通知内审，幂等）")
    @GetMapping("/tamper/sweep")
    public R<Map<String, Object>> sweep() {
        return R.ok(auditLogService.sweepTamperNotices());
    }
}

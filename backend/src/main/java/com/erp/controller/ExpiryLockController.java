package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.ExpiryLockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 效期锁定管理（4.10.2，spec expiry-management 需求②③）：
 * 锁定台账/变更历史/人工锁定/手动扫描。查询认证即可，写口服务层 WAREHOUSE/ADMIN 强制。
 */
@Tag(name = "效期管理-临期锁定")
@RestController
@RequestMapping("/api/inv/expiry-locks")
public class ExpiryLockController {

    private final ExpiryLockService service;

    public ExpiryLockController(ExpiryLockService service) {
        this.service = service;
    }

    @GetMapping("/ledger")
    @Operation(summary = "锁定批次台账（EXPIRY_LOCK_FLAG=1，筛选物料/仓库/锁定来源）")
    public R<Map<String, Object>> ledger(@RequestParam(required = false) String itemCode,
                                         @RequestParam(required = false) String warehouseCode,
                                         @RequestParam(required = false) String lockSource,
                                         @RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.ledger(itemCode, warehouseCode, lockSource, current, size));
    }

    @GetMapping("/history")
    @Operation(summary = "锁定变更历史（flag 变化才落的流水，SCAN/MANUAL/EVAL_RELEASE）")
    public R<List<Map<String, Object>>> history(@RequestParam(required = false) String batchNo,
                                                @RequestParam(required = false) String itemCode,
                                                @RequestParam(defaultValue = "50") long size) {
        return R.ok(service.history(batchNo, itemCode, size));
    }

    @PostMapping("/manual")
    @Operation(summary = "人工锁定（原因必填即刻生效免审批；解除唯一入口=4.10.3 评估放行）")
    public R<Map<String, Object>> manualLock(@RequestParam String batchNo,
                                             @RequestParam String itemCode,
                                             @RequestParam String reason) {
        return R.ok(service.manualLock(batchNo, itemCode, reason));
    }

    @PostMapping("/scan")
    @Operation(summary = "手动触发效期扫描（WAREHOUSE/ADMIN，幂等返回变更批次数）")
    public R<Map<String, Object>> triggerScan() {
        return R.ok(service.triggerScan());
    }
}

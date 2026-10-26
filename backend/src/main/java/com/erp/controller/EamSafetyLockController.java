package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamSafetyLock;
import com.erp.service.eam.EamSafetyLockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 安全停机挂锁（add-asset-maintenance，spec asset-maintenance BR-4.13-16）。
 */
@Tag(name = "资产运维-安全停机")
@RestController
@RequestMapping("/api/eam/safety-locks")
public class EamSafetyLockController {

    private final EamSafetyLockService service;

    public EamSafetyLockController(EamSafetyLockService service) {
        this.service = service;
    }

    @Operation(summary = "分页（卡片号/状态）")
    @GetMapping
    public R<Page<EamSafetyLock>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String cardNo,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, cardNo, status));
    }

    @Operation(summary = "挂锁（工具页/工单执行中）")
    @PostMapping
    public R<Map<String, Object>> lock(@RequestParam String cardId, @RequestParam String reason,
                                       @RequestParam(required = false) String sourceWoNo) {
        return R.ok(service.lock(cardId, reason, sourceWoNo));
    }

    @Operation(summary = "解锁（设备主管）")
    @PostMapping("/{id}/release")
    public R<Map<String, Object>> release(@PathVariable String id,
                                          @RequestParam(required = false) String releaseReason) {
        return R.ok(service.release(id, releaseReason));
    }
}

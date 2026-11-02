package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotOfflineSyncService;
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
 * 离线同步与冲突解决（add-iot-mobile 13.5，FR-4.11-4-x / C-4.11-04/12）。
 * 现场执行口（上传/裁决）= 现场角色（SecurityConfig /api/iot/sync/**）。
 */
@Tag(name = "移动物联-离线同步")
@RestController
@RequestMapping("/api/iot/sync")
public class OfflineSyncController {

    private final IotOfflineSyncService service;

    public OfflineSyncController(IotOfflineSyncService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    @Operation(summary = "批量上传（≤100 条/批，逐条反馈 SYNCED/FAILED/DUPLICATE/CONFLICT）")
    public R<Map<String, Object>> upload(@RequestBody List<Map<String, Object>> records) {
        return R.ok(service.uploadBatch(records));
    }

    @GetMapping("/conflicts")
    @Operation(summary = "冲突列表（三重检测 + BL-4.11-01 字典序裁决留痕）")
    public R<List<Map<String, Object>>> conflicts(@RequestParam(required = false) String status) {
        return R.ok(service.conflictList(status));
    }

    @PostMapping("/conflicts/{id}/resolve")
    @Operation(summary = "冲突裁决（APPROVE 采纳服务端胜者 / REJECT 保留；财务 L2 需审批）")
    public R<Map<String, Object>> resolve(@PathVariable String id,
                                          @RequestBody Map<String, String> body) {
        return R.ok(service.resolveConflict(id, body.get("decision"), body.get("remark")));
    }

    @GetMapping("/lock-status")
    @Operation(summary = "72h 锁定状态（C-4.11-04 预警比例黄闪）")
    public R<Map<String, Object>> lockStatus(@RequestParam String deviceCode) {
        return R.ok(service.lockStatus(deviceCode));
    }

    @GetMapping("/queue")
    @Operation(summary = "离线队列查询（离线作业视图）")
    public R<List<Map<String, Object>>> queue(@RequestParam(required = false) String deviceCode,
                                              @RequestParam(required = false) String status) {
        return R.ok(service.queueList(deviceCode, status));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "同步成功后清理本地缓存（服务端确认清单）")
    public R<Map<String, Object>> cleanup(@RequestParam String deviceCode) {
        return R.ok(service.cleanupSynced(deviceCode));
    }

    @PostMapping("/scan-locks")
    @Operation(summary = "72h 锁定扫描（IotLockScheduler 手动入口）")
    public R<Map<String, Object>> scanLocks() {
        return R.ok(service.scanLocks());
    }
}

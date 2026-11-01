package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ops.OpsAlertService;
import com.erp.service.ops.OpsSyncDelayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 18.2 服务监控：告警台账/认领/处置/风暴聚合视图 + 同步延迟采集与趋势。
 */
@Tag(name = "运维服务监控")
@RestController
@RequestMapping("/api/ops/alerts")
public class OpsAlertController {

    private final OpsAlertService alertService;
    private final OpsSyncDelayService syncDelayService;

    public OpsAlertController(OpsAlertService alertService, OpsSyncDelayService syncDelayService) {
        this.alertService = alertService;
        this.syncDelayService = syncDelayService;
    }

    @Operation(summary = "告警台账（18.2.2）")
    @GetMapping
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(alertService.list(level, source, status, page, size));
    }

    @Operation(summary = "认领告警")
    @PostMapping("/{id}/claim")
    public R<Map<String, Object>> claim(@PathVariable String id,
                                        @RequestParam(required = false) String handler) {
        alertService.claim(id, handler);
        return R.ok(Map.of("status", "CLAIMED"));
    }

    @Operation(summary = "处置完成")
    @PostMapping("/{id}/resolve")
    public R<Map<String, Object>> resolve(@PathVariable String id,
                                          @RequestParam(required = false) String note) {
        alertService.resolve(id, note);
        return R.ok(Map.of("status", "RESOLVED"));
    }

    @Operation(summary = "风暴聚合根因视图（BR-5.8-08）")
    @GetMapping("/storm")
    public R<List<Map<String, Object>>> storm(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(alertService.stormRootCauses(limit));
    }

    @Operation(summary = "同步延迟采集（18.2.1 手动触发）")
    @PostMapping("/sync-delay/collect")
    public R<Map<String, Object>> collectDelay() {
        int n = syncDelayService.collect();
        return R.ok(Map.of("collected", n));
    }

    @Operation(summary = "同步延迟趋势（18.2.3）")
    @GetMapping("/sync-delay/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        return R.ok(syncDelayService.trend(days));
    }
}

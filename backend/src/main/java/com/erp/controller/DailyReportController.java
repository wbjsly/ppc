package com.erp.controller;

import com.erp.common.R;
import com.erp.service.bi.DailyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 日报订阅（add-daily-report-subscription，spec bi-daily-report）。
 * 14.8.1 订阅配置（defs 写限 BI_DESIGNER/ADMIN，订阅本人维度）
 * + 14.8.2 自动推送（push-logs/stats 限 BI_DESIGNER/ADMIN，回执本人）。
 */
@Tag(name = "日报订阅")
@RestController
@RequestMapping("/api/bi/daily-reports")
@RequiredArgsConstructor
public class DailyReportController {

    private final DailyReportService service;

    // ---- 日报定义 ----

    @Operation(summary = "日报定义列表")
    @GetMapping("/defs")
    public R<List<Map<String, Object>>> listDefs() {
        return R.ok(service.listDefs());
    }

    @Operation(summary = "日报定义详情")
    @GetMapping("/defs/{id}")
    public R<Map<String, Object>> getDef(@PathVariable String id) {
        return R.ok(service.getDef(id));
    }

    @Operation(summary = "保存日报定义（新增/更新）")
    @PostMapping("/defs")
    public R<Map<String, Object>> saveDef(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveDef(payload));
    }

    @Operation(summary = "启用/停用日报定义")
    @PutMapping("/defs/{id}/status")
    public R<Void> changeDefStatus(@PathVariable String id, @RequestParam String status) {
        service.changeDefStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除日报定义")
    @DeleteMapping("/defs/{id}")
    public R<Void> deleteDef(@PathVariable String id) {
        service.deleteDef(id);
        return R.ok();
    }

    // ---- 订阅（本人维度） ----

    @Operation(summary = "本人订阅清单")
    @GetMapping("/subscriptions")
    public R<List<Map<String, Object>>> mySubscriptions() {
        return R.ok(service.mySubscriptions());
    }

    @Operation(summary = "创建订阅（指标超权限自动剔除+L4 提示）")
    @PostMapping("/subscriptions")
    public R<Map<String, Object>> saveSubscription(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveSubscription(payload));
    }

    @Operation(summary = "更新订阅通道")
    @PutMapping("/subscriptions/{id}")
    public R<Void> updateSubscription(@PathVariable String id,
                                      @RequestBody List<String> channels) {
        service.updateSubscription(id, channels);
        return R.ok();
    }

    @Operation(summary = "退订（置 DISABLED 留痕）")
    @PostMapping("/subscriptions/{id}/unsubscribe")
    public R<Void> unsubscribe(@PathVariable String id) {
        service.unsubscribe(id);
        return R.ok();
    }

    // ---- 推送与触达 ----

    @Operation(summary = "推送日志分页")
    @GetMapping("/push-logs")
    public R<Map<String, Object>> pagePushLogs(@RequestParam(required = false) String receiver,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.pagePushLogs(receiver, status, page, size));
    }

    @Operation(summary = "触达统计（当月送达率/阅读率/降级数）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(service.stats());
    }

    @Operation(summary = "阅读回执（打开日报详情页；仅本人）")
    @PostMapping("/push-logs/{id}/mark-read")
    public R<Void> markRead(@PathVariable String id) {
        service.markRead(id);
        return R.ok();
    }

    @Operation(summary = "手动触发试推")
    @PostMapping("/push-now")
    public R<Map<String, Object>> pushNow(@RequestParam String reportId) {
        return R.ok(service.pushNow(reportId));
    }
}

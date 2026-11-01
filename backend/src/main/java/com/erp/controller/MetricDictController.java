package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.MetricLineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 指标口径字典控制台（add-metric-dict-console，spec bi-metric-dictionary +
 * bi-metric-lineage）：十二要素登记（14.3.1）、变更治理与血缘（14.3.2）、
 * 批量口径校验（14.3.3）。旧 POST /{key}/formula 直接变更入口已下线（走审批链）。
 */
@Tag(name = "指标口径字典")
@RestController
@RequestMapping("/api/bi/dict")
public class MetricDictController {

    private final MetricDictService service;
    private final MetricLineageService lineageService;

    public MetricDictController(MetricDictService service,
                                MetricLineageService lineageService) {
        this.service = service;
        this.lineageService = lineageService;
    }

    // ---------------- 14.3.1 口径定义 ----------------

    @Operation(summary = "指标分页（仅当前版本）")
    @GetMapping
    public R<Page<BiMetricDict>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @Operation(summary = "登记/更新指标（十二要素；新 key 编码格式校验，要素缺失 400）")
    @PostMapping
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return R.ok(service.register(body));
    }

    @Operation(summary = "发布（RATIO 型分子分母必填；字段级 diff 二次校验，不一致 422 / C-4.10-01）")
    @PostMapping("/{key}/publish")
    public R<Map<String, Object>> publish(@PathVariable String key,
                                          @RequestBody Map<String, Object> body) {
        Object f = body.get("formula");
        return R.ok(service.publish(key, f == null ? null : String.valueOf(f)));
    }

    @Operation(summary = "diff 校验（只读，不阻断）")
    @PostMapping("/{key}/diff")
    public R<Map<String, Object>> diff(@PathVariable String key,
                                       @RequestBody Map<String, Object> body) {
        Object f = body.get("formula");
        return R.ok(service.diffCheck(key, f == null ? null : String.valueOf(f)));
    }

    @Operation(summary = "批量口径校验（14.3.3：PUBLISHED 模板 × 当前版现算报告）")
    @PostMapping("/check-all")
    public R<List<Map<String, Object>>> checkAll() {
        return R.ok(service.checkAll());
    }

    // ---------------- 14.3.2 口径变更（两段式治理 + 血缘） ----------------

    @Operation(summary = "发起口径变更（新版本 CHANGE_PENDING + 治理单签，旧版仍生效）")
    @PostMapping("/{key}/change-request")
    public R<Map<String, Object>> requestChange(@PathVariable String key,
                                                @RequestBody Map<String, Object> body) {
        return R.ok(service.requestChange(key, body));
    }

    @Operation(summary = "变更列表（灰度看板：CHANGE_PENDING/GRAYING/ABORTED）")
    @GetMapping("/{key}/changes")
    public R<List<Map<String, Object>>> changes(@PathVariable String key) {
        return R.ok(service.changes(key));
    }

    @Operation(summary = "切换确认（C-4.10-08 双审；超 0.5% 容差须治理复核备注）")
    @PostMapping("/changes/{rowId}/switch")
    public R<Map<String, Object>> switchConfirm(@PathVariable String rowId,
                                                @RequestBody Map<String, Object> body) {
        Object note = body.get("diffNote");
        Object pct = body.get("diffPct");
        Object review = body.get("reviewNote");
        return R.ok(service.switchConfirm(rowId,
                note == null ? null : String.valueOf(note),
                pct == null ? null : String.valueOf(pct),
                review == null ? null : String.valueOf(review)));
    }

    @Operation(summary = "终止灰度（新版 ABORTED 留痕，旧版续用）")
    @PostMapping("/changes/{rowId}/abort")
    public R<Map<String, Object>> abortChange(@PathVariable String rowId,
                                              @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return R.ok(service.abortChange(rowId, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "引用方更新确认（清除 PENDING_UPDATE 标记）")
    @PutMapping("/{key}/consumers/{cid}/confirm")
    public R<Map<String, Object>> confirmConsumer(@PathVariable String key,
                                                  @PathVariable String cid) {
        return R.ok(service.confirmConsumer(key, cid));
    }

    @Operation(summary = "血缘当前图谱（最新快照，无则现算）")
    @GetMapping("/{key}/lineage")
    public R<Map<String, Object>> lineage(@PathVariable String key) {
        return R.ok(lineageService.graph(key));
    }

    @Operation(summary = "血缘历史版本回溯（按口径版本号）")
    @GetMapping("/{key}/lineage/{version}")
    public R<Map<String, Object>> lineageVersion(@PathVariable String key,
                                                 @PathVariable int version) {
        return R.ok(lineageService.graphVersion(key, version));
    }

    @Operation(summary = "变更影响报告（快照结构化视图 + 高风险标注）")
    @GetMapping("/{key}/lineage/{version}/impact")
    public R<Map<String, Object>> impact(@PathVariable String key,
                                         @PathVariable int version) {
        return R.ok(lineageService.impactReport(key, version));
    }

    @Operation(summary = "手工绑定血缘关系（METRIC 衍生 / TABLE 上游）")
    @PostMapping("/{key}/lineage/bind")
    public R<Map<String, Object>> bindLineage(@PathVariable String key,
                                              @RequestBody Map<String, Object> body) {
        Object type = body.get("nodeType");
        Object id = body.get("nodeId");
        Object name = body.get("nodeName");
        return R.ok(lineageService.bind(key,
                type == null ? null : String.valueOf(type),
                id == null ? null : String.valueOf(id),
                name == null ? null : String.valueOf(name)));
    }

    // ---------------- 引用与回溯 ----------------

    @Operation(summary = "引用方登记")
    @PostMapping("/{key}/usage")
    public R<Map<String, Object>> usage(@PathVariable String key,
                                        @RequestBody Map<String, Object> body) {
        Object c = body.get("consumerId");
        service.bindUsage(key, c == null ? null : String.valueOf(c));
        return R.ok(service.consumerStatus(key));
    }

    @Operation(summary = "引用方状态（页面口径变更提示数据源）")
    @GetMapping("/{key}/consumers")
    public R<Map<String, Object>> consumers(@PathVariable String key) {
        return R.ok(service.consumerStatus(key));
    }

    @Operation(summary = "历史版本（行级回溯）")
    @GetMapping("/{key}/history")
    public R<List<BiMetricDictHistory>> history(@PathVariable String key) {
        return R.ok(service.history(key));
    }
}

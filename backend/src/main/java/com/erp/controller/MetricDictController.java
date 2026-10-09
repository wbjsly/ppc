package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;
import com.erp.service.bi.MetricDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 指标口径字典（spec bi-metric-dictionary，菜单 2.9.x 底座 + 14.x 后续共用）。 */
@Tag(name = "指标口径字典")
@RestController
@RequestMapping("/api/bi/dict")
public class MetricDictController {

    private final MetricDictService service;

    public MetricDictController(MetricDictService service) {
        this.service = service;
    }

    @Operation(summary = "指标分页（仅当前版本）")
    @GetMapping
    public R<Page<BiMetricDict>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @Operation(summary = "登记/更新指标（要素缺失 400）")
    @PostMapping
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return R.ok(service.register(body));
    }

    @Operation(summary = "发布（字段级 diff 二次校验，不一致 422 / C-4.10-01）")
    @PostMapping("/{key}/publish")
    public R<Map<String, Object>> publish(@PathVariable String key,
                                          @RequestBody Map<String, Object> body) {
        Object f = body.get("formula");
        return R.ok(service.publish(key, f == null ? null : String.valueOf(f)));
    }

    @Operation(summary = "公式变更（新版本 + 反向扫描引用方置待更新）")
    @PostMapping("/{key}/formula")
    public R<Map<String, Object>> changeFormula(@PathVariable String key,
                                                @RequestBody Map<String, Object> body) {
        Object f = body.get("newFormula");
        Object reason = body.get("reason");
        return R.ok(service.changeFormula(key, f == null ? null : String.valueOf(f),
                reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "diff 校验（只读，不阻断）")
    @PostMapping("/{key}/diff")
    public R<Map<String, Object>> diff(@PathVariable String key,
                                       @RequestBody Map<String, Object> body) {
        Object f = body.get("formula");
        return R.ok(service.diffCheck(key, f == null ? null : String.valueOf(f)));
    }

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

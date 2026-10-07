package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.service.vmi.VmiDisposalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 寄售库龄处置建议单（2.8.1，spec vmi-consignment，BR-4.2-39 L4）。
 * 读写均放行 ADMIN/PM（SecurityConfig /api/proc/vmi/** 细规则）。
 */
@Tag(name = "寄售库龄处置")
@RestController
@RequestMapping("/api/proc/vmi/disposals")
public class VmiDisposalController {

    private final VmiDisposalService service;

    public VmiDisposalController(VmiDisposalService service) {
        this.service = service;
    }

    @Operation(summary = "处置建议分页（状态/建议类型筛选）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String suggestType) {
        return R.ok(service.page(current, size, status, suggestType));
    }

    @Operation(summary = "惰性扫描生成（台账加载/结算生成时亦自动触发）")
    @PostMapping("/scan")
    public R<Integer> scan() {
        return R.ok(service.scan());
    }

    @Operation(summary = "处理完成（转自有/退回二选一，留痕）")
    @PostMapping("/{id}/resolve")
    public R<Void> resolve(@PathVariable String id,
                           @RequestBody(required = false) Map<String, Object> body) {
        service.resolve(id, body);
        return R.ok(null);
    }

    @Operation(summary = "作废建议单（原因必填）")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable String id,
                          @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        service.cancel(id, reason == null ? null : String.valueOf(reason));
        return R.ok(null);
    }
}

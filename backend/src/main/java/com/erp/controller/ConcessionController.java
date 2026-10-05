package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.qms.Concession;
import com.erp.entity.qms.ConcessionWriteoff;
import com.erp.service.qms.ConcessionService;
import com.erp.service.qms.QualityGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 让步接收（2.5.2 / 6.4.2，spec concession-acceptance）。
 * 双签（质量经理 + 技术负责人并行）经 /api/qms/approvals 签署；本控制器为
 * 申请 / 提交 / 核销 / 查询入口。记录永久：无 DELETE，作废仅 DRAFT → CANCELLED 留痕。
 */
@Tag(name = "让步接收")
@RestController
@RequestMapping("/api/qms/concessions")
public class ConcessionController {

    private final ConcessionService service;
    private final QualityGateService qualityGate;

    public ConcessionController(ConcessionService service, QualityGateService qualityGate) {
        this.service = service;
        this.qualityGate = qualityGate;
    }

    @Operation(summary = "分页（状态/关键字）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "详情（含审批进度、核销记录与剩余额度）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "核销记录")
    @GetMapping("/{id}/writeoffs")
    public R<List<ConcessionWriteoff>> writeoffs(@PathVariable String id) {
        return R.ok(service.writeoffs(id));
    }

    @Operation(summary = "申请（要素必填 422 逐条；仅限本批）")
    @PostMapping
    public R<Concession> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @Operation(summary = "提交双签（质量经理 + 技术负责人并行，缺一不生效）")
    @PostMapping("/{id}/submit")
    public R<Concession> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "作废（仅草稿；记录永久禁硬删）")
    @PostMapping("/{id}/cancel")
    public R<Concession> cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.cancel(id, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "核销放行（有效期/累计量/使用范围逐次校验，越界 422 留痕）")
    @PostMapping("/{id}/writeoffs")
    public R<Map<String, Object>> writeOff(@PathVariable String id, @RequestBody Map<String, Object> body) {
        body.put("concessionId", id);
        return R.ok(service.writeOff(body));
    }

    // ================= 7.5 质量放行闸口（4.5 出库接入桩） =================

    @Operation(summary = "批次放行决策（ALLOWED / CONCESSION / BLOCKED，D8 桩供 4.5 出库调用）")
    @PostMapping("/quality-gate")
    public R<Map<String, Object>> qualityGate(@RequestBody Map<String, Object> body) {
        Object qty = body.get("qty");
        return R.ok(qualityGate.checkIssue(
                body.get("itemCode") == null ? null : String.valueOf(body.get("itemCode")),
                body.get("batchNo") == null ? "" : String.valueOf(body.get("batchNo")),
                qty == null ? null : new BigDecimal(String.valueOf(qty)),
                body.get("scope") == null ? null : String.valueOf(body.get("scope"))));
    }
}

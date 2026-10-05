package com.erp.controller;

import com.erp.common.R;
import com.erp.service.proc.GoodsReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
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
 * 收货管理（2.4 全组，change add-goods-receipt）。
 * SecurityConfig（design D7，须先于 /api/proc 的 ADMIN 规则）：
 *   POST /grs/{id}/postings   -> ADMIN, WAREHOUSE（过账）
 *   POST /grs                 -> ADMIN, RECEIVER（登记与作废）
 *   POST /gr-differences      -> ADMIN（差异处置，采购员职责）
 *   POST /gr-adjustments      -> ADMIN, PM（发起/批准）
 * 顺序要点：具体路径在前，否则 POST /grs 的 RECEIVER 规则会吞掉过账/放行门禁。
 */
@RestController
@RequestMapping("/api/proc")
@RequiredArgsConstructor
public class ReceiptController {

    private final GoodsReceiptService service;

    // ---------- 2.4.1 登记 ----------

    @Operation(summary = "可收货 PO 候选", description = "APPROVED（已下达）PO 列表")
    @GetMapping("/grs/po-candidates")
    public R<Map<String, Object>> poCandidates() {
        return R.ok(service.poCandidates());
    }

    @Operation(summary = "PO 未清行", description = "带出 qty − receivedQty 预填（M-1 默认入口）")
    @GetMapping("/grs/po-lines")
    public R<Map<String, Object>> poLines(@RequestParam String poId) {
        return R.ok(service.poLines(poId));
    }

    @Operation(summary = "按 PO 登记收货", description = "FR-4.2-4-1：容差分流 + 差异单生成（C-4.2-04）")
    @PostMapping("/grs")
    public R<Map<String, Object>> createByPo(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createByPo(payload));
    }

    @Operation(summary = "无 PO 登记收货", description = "FREE 入口：免容差扩展场景（记规格偏差）")
    @PostMapping("/grs/free")
    public R<Map<String, Object>> createFree(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createFree(payload));
    }

    @Operation(summary = "收货单列表", description = "状态/来源/关键字筛选")
    @GetMapping("/grs")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String sourceType,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, status, sourceType, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/grs/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "作废收货单", description = "仅 CREATED，原因必填")
    @PostMapping("/grs/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.cancel(id, reason == null ? null : String.valueOf(reason)));
    }

    // ---------- 2.4.3 差异与调整单 ----------

    @Operation(summary = "差异对账台", description = "L1064 界面要素：跨单差异列表")
    @GetMapping("/gr-differences")
    public R<Map<String, Object>> differencePage(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String diffType,
                                                 @RequestParam(required = false) String keyword) {
        var page = service.differencePage(current, size, status, diffType, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @Operation(summary = "差异处置", description = "START_ADJUST / REJECT / CLOSE（BR-4.2-21/22，无直接接受超收）")
    @PostMapping("/gr-differences/{id}/dispose")
    public R<Map<String, Object>> dispose(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        Object action = body.get("action");
        Object note = body.get("note");
        return R.ok(service.disposeDifference(id,
                action == null ? null : String.valueOf(action),
                note == null ? null : String.valueOf(note)));
    }

    @Operation(summary = "调整单列表", description = "BR-4.2-49 收货调整单")
    @GetMapping("/gr-adjustments")
    public R<Map<String, Object>> adjustmentPage(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String status) {
        var page = service.adjustmentPage(current, size, status);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @Operation(summary = "发起调整单", description = "diffId（超交）或 poLineId+addQty（迟到货物 L1057）")
    @PostMapping("/gr-adjustments")
    public R<Map<String, Object>> createAdjustment(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createAdjustment(payload));
    }

    @Operation(summary = "调整单审批", description = "单节点批准即同事务执行（仅数量增加，价格不动）")
    @PostMapping("/gr-adjustments/{id}/approval")
    public R<Map<String, Object>> approve(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.parseBoolean(String.valueOf(body.get("approved")));
        Object note = body.get("note");
        return R.ok(service.approveAdjustment(id, approved,
                note == null ? null : String.valueOf(note)));
    }

    // ---------- 2.4.2 待检 ----------

    @Operation(summary = "待检看板", description = "数据源=检验批（D1）：风险等级时限 A/B/C 24/48/72h 自登记提交起算")
    @GetMapping("/grs/qc-hold")
    public R<Map<String, Object>> qcPage(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size) {
        var page = service.qcPage(current, size);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }


    @Operation(summary = "风险等级选项", description = "A/B/C（默认 C）")
    @GetMapping("/grs/risk-grade-options")
    public R<List<Map<String, Object>>> riskGradeOptions() {
        return R.ok(service.riskGradeOptions());
    }

    // ---------- 2.4.4 过账 ----------

    @Operation(summary = "入库过账", description = "FR-4.2-6-1 单事务五步；BR-4.2-28 阻断")
    @PostMapping("/grs/{id}/postings")
    public R<Map<String, Object>> posting(@PathVariable String id) {
        return R.ok(service.posting(id));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.PurchaseOrderService;
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
 * 采购订单（2.3.1 订单创建 / 2.3 状态流转，spec purchase-order）。
 * SecurityConfig：POST/PUT/DELETE /api/proc/** → ADMIN。
 */
@Tag(name = "采购订单")
@RestController
@RequestMapping("/api/proc/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;
    private final com.erp.service.proc.PoApprovalService approvalService;
    private final com.erp.service.proc.PriceControlService priceControlService;

    public PurchaseOrderController(PurchaseOrderService service,
                                   com.erp.service.proc.PoApprovalService approvalService,
                                   com.erp.service.proc.PriceControlService priceControlService) {
        this.service = service;
        this.approvalService = approvalService;
        this.priceControlService = priceControlService;
    }

    @Operation(summary = "订单分页", description = "按状态/来源/关键字（PO号、供应商、PR号）检索")
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, status, source, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @Operation(summary = "订单详情", description = "头 + 行（协议/PR 溯源、价控结果）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "从协议下单", description = "带出锁定价 + 余量校验（S-4.2-03）；到期/终止协议阻断（L1059）")
    @PostMapping("/agreement-orders")
    public R<Map<String, Object>> createFromAgreement(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createFromAgreement(payload));
    }

    @Operation(summary = "RFQ 中选转 PO", description = "消费 awarded(prNo) 桩（供 2.3.1），单价=中选价，PR 回写 BR-4.2-51")
    @PostMapping("/rfq-orders")
    public R<Map<String, Object>> createFromRfq(@RequestBody Map<String, Object> body) {
        Object prNo = body.get("prNo");
        if (prNo == null || String.valueOf(prNo).trim().isEmpty()) {
            throw new ServiceException(422, "prNo 必填");
        }
        return R.ok(service.createFromRfq(String.valueOf(prNo).trim()));
    }

    @Operation(summary = "手工创建订单", description = "供应商合格卡控 BR-4.2-18；价控提交时执行（组 7）")
    @PostMapping
    public R<Map<String, Object>> createManual(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createManual(payload));
    }

    @Operation(summary = "提交审批", description = "价控三重 → 三档判级节点；body 可带 specialReason 特批转升级链（组 7）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String specialReason = body == null ? null : str(body.get("specialReason"));
        return R.ok(service.submit(id, specialReason));
    }

    @Operation(summary = "价控校验日志", description = "合同价/历史价/预算三段日志（BR-4.2-19，2.3.5 页面）")
    @GetMapping("/{id}/price-logs")
    public R<List<Map<String, Object>>> priceLogs(@PathVariable String id) {
        return R.ok(priceControlService.logs(id));
    }

    // ---------- 2.3.3/2.3.4 变更与版本（FR-4.2-9） ----------

    @Operation(summary = "发起变更", description = "五类型；快照旧版本；金额增加分级审批（>容差升级采购总监），调减留痕（BR-4.2-04）")
    @PostMapping("/{id}/changes")
    public R<Map<String, Object>> change(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.change(id, payload));
    }

    @Operation(summary = "版本列表", description = "变更类型/原因/审批状态/要求角色（旧版本只读凭据）")
    @GetMapping("/{id}/versions")
    public R<List<Map<String, Object>>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @Operation(summary = "变更审批", description = "通过置 APPROVED；驳回自动回退至变更前状态（FR-4.2-9-2）")
    @PostMapping("/{id}/versions/{versionNo}/approve")
    public R<Map<String, Object>> approveChange(@PathVariable String id,
                                                @PathVariable int versionNo,
                                                @RequestBody Map<String, Object> body) {
        Object ap = body.get("approved");
        boolean approved = ap != null && Boolean.parseBoolean(String.valueOf(ap));
        return R.ok(service.approveChange(id, versionNo, approved, str(body.get("reason"))));
    }

    @Operation(summary = "回滚到历史版本", description = "生成新版本（BR-4.2-03 回滚生成新版本，旧版本永久只读）")
    @PostMapping("/{id}/versions/{versionNo}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable String id,
                                           @PathVariable int versionNo,
                                           @RequestBody Map<String, Object> body) {
        return R.ok(service.rollback(id, versionNo));
    }

    @Operation(summary = "手工关闭", description = "DRAFT/APPROVED → CLOSED，原因必填（偏差 D5：收货闭环属 2.4）")
    @PostMapping("/{id}/close")
    public R<Map<String, Object>> close(@PathVariable String id,
                                        @RequestBody Map<String, Object> body) {
        return R.ok(service.close(id, str(body.get("reason"))));
    }

    // ---------- 2.3.2 订单审批（design D1 独立三档） ----------

    @Operation(summary = "审批待办", description = "ACTIVE 节点 + PO 摘要 + 路由链 + 判级档位 + 价控结果（FR-4.2-3-2）")
    @GetMapping("/approval-todo")
    public R<List<Map<String, Object>>> approvalTodo() {
        return R.ok(approvalService.todo());
    }

    @Operation(summary = "节点通过", description = "末节点 → PO 已批准（已下达）；否则激活下一节点")
    @PostMapping("/tasks/{taskId}/pass")
    public R<List<Map<String, Object>>> pass(@PathVariable String taskId) {
        return R.ok(approvalService.pass(taskId));
    }

    @Operation(summary = "条件批准", description = "附加条件必填（≥2 字），同通过流转并留痕")
    @PostMapping("/tasks/{taskId}/pass-conditional")
    public R<List<Map<String, Object>>> passConditional(@PathVariable String taskId,
                                                        @RequestBody Map<String, Object> body) {
        return R.ok(approvalService.passConditional(taskId, str(body.get("conditionText"))));
    }

    @Operation(summary = "节点驳回", description = "原因 ≥2 字；余节点作废，PO 回草稿可改重提（批次 +1）")
    @PostMapping("/tasks/{taskId}/reject")
    public R<List<Map<String, Object>>> reject(@PathVariable String taskId,
                                               @RequestBody Map<String, Object> body) {
        return R.ok(approvalService.reject(taskId, str(body.get("reason"))));
    }

    @Operation(summary = "审批日志", description = "按 PO 的批次/节点/动作/原因/人/时间倒序")
    @GetMapping("/{id}/approval-logs")
    public R<List<Map<String, Object>>> approvalLogs(@PathVariable String id) {
        return R.ok(approvalService.logs(id));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

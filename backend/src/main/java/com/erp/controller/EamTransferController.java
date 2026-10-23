package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamTransferOrder;
import com.erp.service.eam.EamTransferService;
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
 * 资产调拨（add-asset-transfer-lease，spec asset-transfer-lease）。
 * 7.4.1 部门调拨 / 7.4.2 法人调拨（同页 TYPE 视图）+ 共享池 + C-10 月结卡控。
 */
@Tag(name = "资产调拨")
@RestController
@RequestMapping("/api/eam/transfers")
public class EamTransferController {

    private final EamTransferService service;

    public EamTransferController(EamTransferService service) {
        this.service = service;
    }

    @Operation(summary = "调拨单分页（TRANSFER_TYPE=DEPT/CROSS_LE）")
    @GetMapping
    public R<Page<EamTransferOrder>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String transferType,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String cardNo) {
        return R.ok(service.page(current, size, transferType, status, cardNo));
    }

    @Operation(summary = "调拨单详情（含卡片）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建调拨申请（BR-21 四前置聚合校验，不满足 422 逐项列示）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody EamTransferOrder order) {
        return R.ok(service.create(order));
    }

    @Operation(summary = "提交审批（DEPT 三节点 / CROSS_LE 四节点）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "实物交接确认（direction=OUT 调出方 / IN 调入方；双齐置 HANDOVERED）")
    @PostMapping("/{id}/handover")
    public R<Map<String, Object>> handover(@PathVariable String id,
                                           @RequestParam String direction) {
        return R.ok(service.handoverConfirm(id, direction));
    }

    @Operation(summary = "确认调拨完成（交接双确认校验→即改卡片→CROSS_LE 内部交易同事务）")
    @PostMapping("/{id}/complete")
    public R<Map<String, Object>> complete(@PathVariable String id) {
        return R.ok(service.completeFromApproval(id));
    }

    @Operation(summary = "C-4.13-10 月结卡控：内部交易/双凭证不齐 → 422 列清单")
    @GetMapping("/interco/check")
    public R<Map<String, Object>> intercoCheck() {
        return R.ok(service.assertIntercoComplete());
    }

    @Operation(summary = "闲置共享池（≥12 月入池可调拨 / ≥24 月处置评估待办，BR-4.13-05）")
    @GetMapping("/idle-pool")
    public R<List<Map<String, Object>>> idlePool() {
        return R.ok(service.idlePool());
    }
}

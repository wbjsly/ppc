package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.service.inv.InternalTransferAccountingService;
import com.erp.service.inv.TransferOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 调拨单（4.12.1/4.12.2；4.5.3/4.4.4 过账动作经作业台委托同一服务，
 * spec transfer-order——三入口共享同一后端动作）。查询认证即可，写限 ADMIN/WAREHOUSE。
 */
@Tag(name = "调拨单")
@RestController
@RequestMapping("/api/inv/transfers")
public class TransferOrderController {

    private final TransferOrderService service;
    private final InternalTransferAccountingService accounting;

    public TransferOrderController(TransferOrderService service,
                                   InternalTransferAccountingService accounting) {
        this.service = service;
        this.accounting = accounting;
    }

    /** 创建入参：头字段 + lines 数组（Map 解包，避免嵌套绑定歧义） */
    public static class SavePayload {
        public InvTransferOrder head;
        public List<InvTransferOrderLine> lines;
    }

    @PostMapping
    @Operation(summary = "创建调拨单（DRAFT，行内部转移价必填）")
    public R<Map<String, Object>> create(@RequestBody SavePayload payload) {
        return R.ok(service.create(payload.head, payload.lines));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑调拨单（仅 DRAFT，整单行重建）")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody SavePayload payload) {
        return R.ok(service.update(id, payload.head, payload.lines));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "作废（仅 DRAFT，原因必填）")
    public R<Void> cancel(@PathVariable String id, @RequestParam String reason) {
        service.cancel(id, reason);
        return R.ok(null);
    }

    @PostMapping("/{id}/post-out")
    @Operation(summary = "出库段过账（DRAFT → OUT_POSTED，引擎 TRANSFER_OUT）")
    public R<Map<String, Object>> postOut(@PathVariable String id) {
        return R.ok(service.postOut(id));
    }

    @PostMapping("/{id}/post-in")
    @Operation(summary = "入库段过账（OUT_POSTED → IN_POSTED，引擎 TRANSFER_IN）")
    public R<Map<String, Object>> postIn(@PathVariable String id) {
        return R.ok(service.postIn(id));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭（IN_POSTED → CLOSED，跨法人先过核销校验）")
    public R<Map<String, Object>> close(@PathVariable String id) {
        return R.ok(service.close(id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "详情（头+行+分配明细）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping
    @Operation(summary = "4.12.1 调拨单列表（status/keyword 过滤）")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @GetMapping("/intransit")
    @Operation(summary = "4.12.2 在途跟踪（仅 OUT_POSTED，含在途天数与挂起标记）")
    public R<Map<String, Object>> intransit(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(service.intransitPage(current, size, keyword));
    }

    @GetMapping("/internal-ledger")
    @Operation(summary = "F2 内部往来视图（ISSUED 未核销按法人对+方向汇总）")
    public R<Map<String, Object>> internalLedger() {
        return R.ok(accounting.receivableView());
    }

    @GetMapping("/internal-ledger/detail")
    @Operation(summary = "F2 逐单下钻（法人对+方向的内部发票明细）")
    public R<Map<String, Object>> internalLedgerDetail(@RequestParam(required = false) String outLe,
                                                        @RequestParam(required = false) String inLe,
                                                        @RequestParam(required = false) String direction,
                                                        @RequestParam(required = false) String status) {
        return R.ok(accounting.receivableDetail(outLe, inLe, direction, status));
    }
}

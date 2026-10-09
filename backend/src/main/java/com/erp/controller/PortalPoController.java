package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.security.PortalContext;
import com.erp.service.proc.PoCoopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 门户我的 PO（spec po-collaboration + supplier-portal-account 行级隔离）：
 * 所有查询/动作强制绑定 supplierId，越权访问他人 PO → 404（默认拒绝）。
 */
@Tag(name = "门户-我的PO")
@RestController
@RequestMapping("/api/portal/pos")
public class PortalPoController {

    private final PoCoopService coopService;
    private final PurchaseOrderDao poDao;
    private final com.erp.dao.proc.PurchaseOrderLineDao lineDao;
    private final PortalContext portalContext;

    public PortalPoController(PoCoopService coopService,
                              PurchaseOrderDao poDao,
                              com.erp.dao.proc.PurchaseOrderLineDao lineDao,
                              PortalContext portalContext) {
        this.coopService = coopService;
        this.poDao = poDao;
        this.lineDao = lineDao;
        this.portalContext = portalContext;
    }

    @Operation(summary = "我的 PO（待确认/已确认）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(coopService.portalPage(current, size, keyword, supplierId));
    }

    @Operation(summary = "我的 PO 协同时间线")
    @GetMapping("/{poId}/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable String poId) {
        requireOwn(poId);
        return R.ok(coopService.timeline(poId));
    }

    @Operation(summary = "我的 PO 明细（头+行，越权 404）")
    @GetMapping("/{poId}")
    public R<Map<String, Object>> detail(@PathVariable String poId) {
        requireOwn(poId);
        PurchaseOrder po = poDao.selectById(poId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("po", po);
        out.put("lines", lineDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.proc.PurchaseOrderLine>()
                        .eq(com.erp.entity.proc.PurchaseOrderLine::getPoId, poId)
                        .orderByAsc(com.erp.entity.proc.PurchaseOrderLine::getLineNo)));
        return R.ok(out);
    }

    @Operation(summary = "交期确认（门户，来源 PORTAL）")
    @PostMapping("/{poId}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String poId,
                                          @RequestBody(required = false) Map<String, Object> payload) {
        requireOwn(poId);
        return R.ok(coopService.confirm(poId, payload == null ? Map.of() : payload, "PORTAL"));
    }

    @Operation(summary = "改期/改量申请（门户）")
    @PostMapping("/{poId}/change-request")
    public R<Map<String, Object>> changeRequest(@PathVariable String poId,
                                                @RequestBody Map<String, Object> payload) {
        requireOwn(poId);
        return R.ok(coopService.changeRequest(poId, payload));
    }

    /** 行级隔离：PO 必须属于当前绑定供应商，否则 404（C-4.9-06 默认拒绝） */
    private void requireOwn(String poId) {
        String supplierId = portalContext.requireSupplierId();
        PurchaseOrder po = poDao.selectOne(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, poId));
        if (po == null || !supplierId.equals(po.getSupplierId())) {
            throw new ServiceException(404, "采购订单不存在");
        }
    }
}

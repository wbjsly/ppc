package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.dao.proc.AsnDao;
import com.erp.security.PortalContext;
import com.erp.service.proc.AsnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 门户我的 ASN（spec asn-collaboration + supplier-portal-account 行级隔离）。
 */
@Tag(name = "门户-我的ASN")
@RestController
@RequestMapping("/api/portal/asns")
public class PortalAsnController {

    private final AsnService service;
    private final AsnDao asnDao;
    private final PurchaseOrderDao poDao;
    private final PortalContext portalContext;

    public PortalAsnController(AsnService service, AsnDao asnDao,
                               PurchaseOrderDao poDao, PortalContext portalContext) {
        this.service = service;
        this.asnDao = asnDao;
        this.poDao = poDao;
        this.portalContext = portalContext;
    }

    @Operation(summary = "我的 ASN")
    @GetMapping
    public R<Page<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(service.page(current, size, status, supplierId, keyword));
    }

    @Operation(summary = "我的 ASN 明细（越权 404）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        requireOwn(id);
        return R.ok(service.detail(id));
    }

    @Operation(summary = "创建 ASN（按已确认交期的 PO）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        String supplierId = portalContext.requireSupplierId();
        Object poId = payload.get("poId");
        if (poId == null) {
            throw new ServiceException(422, "poId 必填");
        }
        PurchaseOrder po = poDao.selectById(String.valueOf(poId));
        if (po == null || !supplierId.equals(po.getSupplierId())) {
            // 行级隔离：他人 PO 一律 404（C-4.9-06 默认拒绝）
            throw new ServiceException(404, "采购订单不存在");
        }
        return R.ok(service.create(payload));
    }

    private void requireOwn(String id) {
        String supplierId = portalContext.requireSupplierId();
        Asn a = asnDao.selectOne(new LambdaQueryWrapper<Asn>().eq(Asn::getId, id));
        if (a == null || !supplierId.equals(a.getSupplierId())) {
            throw new ServiceException(404, "ASN 不存在");
        }
    }
}

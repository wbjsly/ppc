package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinSupplierStatementDao;
import com.erp.entity.fin.FinSupplierStatement;
import com.erp.security.PortalContext;
import com.erp.service.fin.SupplierStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 门户我的对账（spec supplier-statement-reconciliation ADDED：门户可见自身匹配结果，行级隔离）。
 * 冻结与双签动作不开放门户（归 2.7.3，Q5 分工）。
 */
@Tag(name = "门户-我的对账")
@RestController
@RequestMapping("/api/portal/statements")
public class PortalStatementController {

    private final SupplierStatementService service;
    private final FinSupplierStatementDao stmtDao;
    private final PortalContext portalContext;

    public PortalStatementController(SupplierStatementService service,
                                     FinSupplierStatementDao stmtDao,
                                     PortalContext portalContext) {
        this.service = service;
        this.stmtDao = stmtDao;
        this.portalContext = portalContext;
    }

    @Operation(summary = "我的对账单（含匹配结果与差异清单）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        String supplierId = portalContext.requireSupplierId();
        return R.ok(service.page(current, size, supplierId, status, null, null));
    }

    @Operation(summary = "我的对账单详情（越权 404）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        String supplierId = portalContext.requireSupplierId();
        FinSupplierStatement s = stmtDao.selectById(id);
        if (s == null || !supplierId.equals(s.getSupplierId())) {
            throw new ServiceException(404, "对账单不存在");
        }
        return R.ok(service.detail(id));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.ProcRequisitionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 请购单（采购管理 2.1.1/2.1.2，/api/proc/requisitions）。
 * SecurityConfig：POST/PUT/DELETE /api/proc/** → ADMIN。
 */
@RestController
@RequestMapping("/api/proc/requisitions")
public class ProcRequisitionController {

    private final ProcRequisitionService service;

    public ProcRequisitionController(ProcRequisitionService service) {
        this.service = service;
    }

    @GetMapping
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

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    /** 手工创建（2.1.2） */
    @PostMapping
    public R<Map<String, Object>> createManual(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createManual(payload));
    }

    /** 手工编辑（可改态；预算清空回落 PENDING_BUDGET） */
    @PutMapping("/{id}")
    public R<Map<String, Object>> updateManual(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateManual(id, payload));
    }

    @DeleteMapping("/{id}")
    public R<Void> deleteManual(@PathVariable String id) {
        service.deleteManual(id);
        return R.ok();
    }

    /** MRP 行编辑保存（待确认/已驳回态） */
    @PutMapping("/{id}/lines")
    public R<Void> updateLines(@PathVariable String id, @RequestBody Map<String, Object> body) {
        service.updateLines(id, rows(body));
        return R.ok();
    }

    /** 整单确认（80% 卡控 BR-4.2-08） */
    @PostMapping("/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    /** 行建议供应商（已确认/已批准/待询价态） */
    @PutMapping("/lines/{lineId}/supplier")
    public R<Void> setSupplier(@PathVariable String lineId,
                               @RequestBody Map<String, Object> body) {
        service.setLineSupplier(lineId, str(body.get("supplierId")));
        return R.ok();
    }

    /** 流转询价（APPROVED→PENDING_RFQ） */
    @PostMapping("/{id}/route")
    public R<Map<String, Object>> route(@PathVariable String id) {
        return R.ok(service.routeToRfq(id));
    }

    /** 整单手工关闭（原因必填） */
    @PostMapping("/{id}/close")
    public R<Void> closePr(@PathVariable String id, @RequestBody Map<String, Object> body) {
        service.closePr(id, str(body.get("reason")));
        return R.ok();
    }

    /** 行手工关闭 */
    @PostMapping("/lines/{lineId}/close")
    public R<Void> closeLine(@PathVariable String lineId, @RequestBody Map<String, Object> body) {
        service.closeLine(lineId, str(body.get("reason")));
        return R.ok();
    }

    /** 交付计划行整单替换 */
    @PutMapping("/lines/{lineId}/delivery-lines")
    public R<List<Map<String, Object>>> saveDelivery(@PathVariable String lineId,
                                                     @RequestBody Map<String, Object> body) {
        return R.ok(service.saveDeliveryLines(lineId, rows(body)));
    }

    /** PO 下达回写桩（BR-4.2-51） */
    @PostMapping("/lines/{lineId}/allocation")
    public R<Map<String, Object>> allocation(@PathVariable String lineId,
                                             @RequestBody Map<String, Object> body) {
        Object q = body.get("qty");
        if (q == null || String.valueOf(q).isBlank()) {
            throw new ServiceException(422, "qty 必填");
        }
        try {
            return R.ok(service.receivePoAllocation(lineId, new BigDecimal(String.valueOf(q).trim())));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "qty 格式非法");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> body) {
        Object rows = body.get("lines") != null ? body.get("lines") : body.get("rows");
        if (!(rows instanceof List)) {
            throw new ServiceException(422, "lines/rows 必填");
        }
        return (List<Map<String, Object>>) rows;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

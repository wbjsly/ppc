package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.RfqService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 询价比价（2.2.1，/api/proc/rfqs）。
 * SecurityConfig：POST/PUT/DELETE /api/proc/** → ADMIN（写）。
 */
@RestController
@RequestMapping("/api/proc")
public class RfqController {

    private final RfqService service;

    public RfqController(RfqService service) {
        this.service = service;
    }

    @GetMapping("/rfqs")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, status, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/rfqs/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    /** PR 候选（待询价 + 紧急放行状态） */
    @GetMapping("/rfqs/pr-candidates")
    public R<Map<String, Object>> prCandidates() {
        return R.ok(service.prCandidates());
    }

    /** 中选桩（供 2.3.1） */
    @GetMapping("/rfqs/awarded")
    public R<Map<String, Object>> awarded(@RequestParam String prNo) {
        return R.ok(service.awarded(prNo));
    }

    /** 比价矩阵（权重即时计算；±20% 异常自动持久标记） */
    @GetMapping("/rfqs/{id}/matrix")
    public R<Map<String, Object>> matrix(@PathVariable String id,
                                         @RequestParam(required = false) Integer weightPrice,
                                         @RequestParam(required = false) Integer weightDelivery) {
        return R.ok(service.matrix(id, weightPrice, weightDelivery));
    }

    /** 创建（C-4.2-01 卡控 + 紧急放行例外） */
    @PostMapping("/rfqs")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    /** 发出询价 */
    @PostMapping("/rfqs/{id}/send")
    public R<Map<String, Object>> send(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        return R.ok(service.send(id, str(body.get("sendMode"))));
    }

    /** 报价录入/覆盖 */
    @PostMapping("/rfqs/{id}/quotes")
    public R<Map<String, Object>> saveQuote(@PathVariable String id,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(service.saveQuote(id, payload));
    }

    /** 延期截止日（未锁价） */
    @PostMapping("/rfqs/{id}/postpone")
    public R<Map<String, Object>> postpone(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        return R.ok(service.postpone(id, str(body.get("newDeadline")), str(body.get("reason"))));
    }

    /** 追加供应商（未锁价） */
    @PostMapping("/rfqs/{id}/suppliers")
    public R<Map<String, Object>> addSuppliers(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        return R.ok(service.addSuppliers(id, strList(body.get("supplierIds"))));
    }

    /** 作废重询/人工关闭 */
    @PostMapping("/rfqs/{id}/close")
    public R<Void> close(@PathVariable String id, @RequestBody Map<String, Object> body) {
        service.closeRfq(id, str(body.get("reason")));
        return R.ok();
    }

    /** 异常确认保留 */
    @PostMapping("/rfqs/quotes/{quoteId}/confirm-anomaly")
    public R<Map<String, Object>> confirmAnomaly(@PathVariable String quoteId) {
        return R.ok(service.confirmAnomaly(quoteId));
    }

    /** 剔除报价 */
    @PostMapping("/rfqs/quotes/{quoteId}/exclude")
    public R<Map<String, Object>> exclude(@PathVariable String quoteId,
                                          @RequestBody Map<String, Object> body) {
        return R.ok(service.excludeQuote(quoteId, str(body.get("reason"))));
    }

    /** 谈判双轨 */
    @PostMapping("/rfqs/quotes/{quoteId}/negotiate")
    public R<Map<String, Object>> negotiate(@PathVariable String quoteId,
                                            @RequestBody Map<String, Object> body) {
        Object p = body.get("price");
        if (p == null || String.valueOf(p).isBlank()) {
            throw new ServiceException(422, "谈判后单价必填");
        }
        try {
            return R.ok(service.negotiate(quoteId,
                    new BigDecimal(String.valueOf(p).trim()), str(body.get("note"))));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "谈判后单价格式非法");
        }
    }

    /** 定标（六前置 → AWARDED） */
    @PostMapping("/rfqs/{id}/award")
    public R<Map<String, Object>> award(@PathVariable String id,
                                        @RequestBody Map<String, Object> payload) {
        return R.ok(service.award(id, payload));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        if (!(o instanceof List)) {
            throw new ServiceException(422, "supplierIds 必填");
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object x : (List<Object>) o) {
            if (x != null) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }
}

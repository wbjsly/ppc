package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.qms.CustomerAgreement;
import com.erp.service.qms.CustomerAgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户质量协议（6.2 检验任务，spec customer-quality-agreement）。
 * 写权限：SecurityConfig → QUALITY_ENG / QUALITY_MGR / ADMIN。
 */
@Tag(name = "客户质量协议")
@RestController
@RequestMapping("/api/qms/agreements")
public class CustomerAgreementController {

    private final CustomerAgreementService service;

    public CustomerAgreementController(CustomerAgreementService service) {
        this.service = service;
    }

    @Operation(summary = "协议列表")
    @GetMapping
    public R<List<CustomerAgreement>> list(@RequestParam(required = false) String customerId,
                                           @RequestParam(required = false) String status) {
        return R.ok(service.list(customerId, status));
    }

    @Operation(summary = "协议详情（含投诉留痕）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "新建/更新草稿协议")
    @PostMapping
    public R<CustomerAgreement> save(@RequestBody CustomerAgreement agreement) {
        return R.ok(service.save(agreement));
    }

    @Operation(summary = "草稿生效（区间重叠 422）")
    @PostMapping("/{id}/activate")
    public R<CustomerAgreement> activate(@PathVariable String id) {
        return R.ok(service.activate(id));
    }

    @Operation(summary = "停用（仅阻止新批命中，历史不变）")
    @PostMapping("/{id}/stop")
    public R<CustomerAgreement> stop(@PathVariable String id) {
        return R.ok(service.stop(id));
    }

    @Operation(summary = "投诉登记（计数 +1，原因必填）")
    @PostMapping("/{id}/complaint")
    public R<CustomerAgreement> complaint(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return R.ok(service.logComplaint(id, reason == null ? null : String.valueOf(reason)));
    }

    @Operation(summary = "投诉计数清零")
    @PostMapping("/{id}/clear")
    public R<CustomerAgreement> clear(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return R.ok(service.clearStreak(id, reason == null ? null : String.valueOf(reason)));
    }
}

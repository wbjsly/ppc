package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmSubcontractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 外协合同台账与付款（add-cost-collection-freeze，FR-4.8-5-2）。 */
@Tag(name = "外协合同与付款")
@RestController
@RequestMapping("/api/pm/cost/subcontracts")
@RequiredArgsConstructor
public class PmSubcontractController {

    private final PmSubcontractService service;

    @GetMapping("/contracts")
    @Operation(summary = "合同台账列表（含剩余余额）")
    public R<List<Map<String, Object>>> listContracts(@RequestParam(required = false) String projectId) {
        return R.ok(service.listContracts(projectId));
    }

    @PostMapping("/contracts")
    @Operation(summary = "新建/更新合同（WBS 可空 → L4 标记）")
    public R<Map<String, Object>> saveContract(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveContract(payload));
    }

    @GetMapping("/payments")
    @Operation(summary = "付款单列表")
    public R<List<Map<String, Object>>> listPayments(@RequestParam(required = false) String projectId,
                                                     @RequestParam(required = false) String contractId,
                                                     @RequestParam(required = false) String status) {
        return R.ok(service.listPayments(projectId, contractId, status));
    }

    @PostMapping("/payments")
    @Operation(summary = "保存付款草稿（超合同余额 L1 阻断；冻结项目 422）")
    public R<Map<String, Object>> savePayment(@RequestBody Map<String, Object> payload) {
        return R.ok(service.savePayment(payload, false));
    }

    @PostMapping("/payments/submit")
    @Operation(summary = "提交付款单（占合同余额）")
    public R<Map<String, Object>> submitPayment(@RequestBody Map<String, Object> payload) {
        return R.ok(service.savePayment(payload, true));
    }

    @PostMapping("/payments/{id}/review")
    @Operation(summary = "审核（通过→外协成本归集+凭证+回写已付累计）")
    public R<Map<String, Object>> reviewPayment(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.reviewPayment(id, payload));
    }
}

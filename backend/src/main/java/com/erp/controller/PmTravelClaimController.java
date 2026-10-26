package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmTravelClaimService;
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

/** 职级差旅标准与差旅报销（add-cost-collection-freeze，FR-4.8-5-3）。 */
@Tag(name = "差旅报销")
@RestController
@RequestMapping("/api/pm/cost/travels")
@RequiredArgsConstructor
public class PmTravelClaimController {

    private final PmTravelClaimService service;

    @GetMapping("/standards")
    @Operation(summary = "差旅标准列表（职级×城市类别）")
    public R<List<Map<String, Object>>> listStandards() {
        return R.ok(service.listStandards());
    }

    @PostMapping("/standards")
    @Operation(summary = "保存标准（覆盖同职级同城市类别）")
    public R<Map<String, Object>> saveStandard(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveStandard(payload));
    }

    @GetMapping("/claims")
    @Operation(summary = "报销单列表")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String projectId,
                                             @RequestParam(required = false) String empId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(projectId, empId, status));
    }

    @PostMapping("/claims")
    @Operation(summary = "保存草稿（超标必填原因；DEPT_ONLY 语义）")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, false));
    }

    @PostMapping("/claims/submit")
    @Operation(summary = "提交（超标自动发起 L2 财务审批；标准内进审核队列）")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload, true));
    }

    @PostMapping("/claims/{id}/review")
    @Operation(summary = "常规审核（仅标准内单据；通过→差旅成本归集+凭证）")
    public R<Map<String, Object>> review(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.review(id, payload));
    }
}

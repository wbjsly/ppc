package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.crm.Contract;
import com.erp.service.crm.ContractService;
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
 * 销售合同接口（tasks 14.7，11.11.1 合同签订 / 11.11.2 收款计划 / 11.11.3 合同变更，
 * spec sales-contract）。权限：SALES / SALES_MGR / FINANCE_MGR / ADMIN。
 */
@Tag(name = "销售合同")
@RestController
@RequestMapping("/api/crm/contracts")
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "合同分页（客户/状态/关键字筛选）")
    public R<Page<Contract>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String customerId) {
        return R.ok(service.page(current, size, keyword, status, customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "详情（SO 链/计划达成/应收汇总/版本历史/审批日志/调整提示）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @Operation(summary = "生成合同草稿（商机带出，CT_NO 生成锁定，FR-4.8-1-7）")
    public R<Contract> createDraft(@RequestBody Map<String, Object> req) {
        return R.ok(service.createDraft(req));
    }

    @PostMapping("/{id}/update")
    @Operation(summary = "修改草稿（编号与商机金额基准不可改）")
    public R<Contract> update(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return R.ok(service.update(id, req));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交：差异 >10% → L2（销售经理→总监），否则直接法务审核")
    public R<Contract> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/legal-review")
    @Operation(summary = "法务审核占位（审核人/意见必填；驳回退回修改）")
    public R<Contract> legalReview(@PathVariable String id,
                                   @RequestBody Map<String, Object> body) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        String reviewer = body.get("reviewer") == null ? null
                : String.valueOf(body.get("reviewer"));
        String opinion = body.get("opinion") == null ? null
                : String.valueOf(body.get("opinion"));
        return R.ok(service.legalReview(id, pass, reviewer, opinion));
    }

    @PostMapping("/{id}/plans")
    @Operation(summary = "收款计划全量保存（不参与记账；合计不符返回待调整提示，14.4）")
    public R<Map<String, Object>> savePlans(@PathVariable String id,
                                            @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> plans = body.get("plans") == null ? List.of()
                : new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                body.get("plans"), new com.fasterxml.jackson.databind.ObjectMapper()
                        .getTypeFactory().constructCollectionType(List.class, Map.class));
        return R.ok(service.savePlans(id, plans));
    }

    @PostMapping("/plans/{planId}/delete")
    @Operation(summary = "删除收款期次")
    public R<Void> removePlan(@PathVariable String planId) {
        service.removePlan(planId);
        return R.ok();
    }

    @GetMapping("/{id}/progress")
    @Operation(summary = "14.5 计划达成对比（合同→SO→应收链路，超期标红）")
    public R<Map<String, Object>> progress(@PathVariable String id) {
        return R.ok(service.progress(id));
    }

    @PostMapping("/{id}/change")
    @Operation(summary = "14.6 变更（新版本+销售经理审批，差异扩大升级 L2）")
    public R<Contract> change(@PathVariable String id,
                              @RequestBody Map<String, Object> payload) {
        return R.ok(service.change(id, payload));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "版本历史（只读快照）")
    public R<List<Map<String, Object>>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }
}

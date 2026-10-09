package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.RebateBudget;
import com.erp.entity.sd.RebatePolicy;
import com.erp.entity.sd.RebateSettlement;
import com.erp.entity.sd.RebateTarget;
import com.erp.service.sd.RebateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 返利结算接口（tasks 11.9，3.9.1 返利计算 / 3.9.2 返利审批 / 3.9.3 返利兑现，
 * spec sales-rebate）。权限：SALES_MGR / SALES_DIRECTOR / FINANCE_MGR / ADMIN。
 */
@Tag(name = "返利结算")
@RestController
@RequestMapping("/api/sd/rebate")
public class RebateController {

    private final RebateService service;

    public RebateController(RebateService service) {
        this.service = service;
    }

    // ---------- 三配置（3.9.1 页内 Tab） ----------

    @GetMapping("/targets")
    @Operation(summary = "季度销售目标分页")
    public R<Page<RebateTarget>> targets(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String customerId,
                                         @RequestParam(required = false) String quarter) {
        return R.ok(service.targetPage(current, size, customerId, quarter));
    }

    @PostMapping("/targets")
    @Operation(summary = "保存季度目标（同客户同季度唯一）")
    public R<RebateTarget> saveTarget(@RequestBody RebateTarget target) {
        return R.ok(service.saveTarget(target));
    }

    @PostMapping("/targets/{id}/delete")
    @Operation(summary = "删除季度目标")
    public R<Void> removeTarget(@PathVariable String id) {
        service.removeTarget(id);
        return R.ok(null);
    }

    @GetMapping("/policies")
    @Operation(summary = "返利政策阶梯列表")
    public R<List<RebatePolicy>> policies(@RequestParam(required = false) String customerId) {
        return R.ok(service.policyList(customerId));
    }

    @PostMapping("/policies")
    @Operation(summary = "保存政策阶梯（新增/更新）")
    public R<RebatePolicy> savePolicy(@RequestBody RebatePolicy policy) {
        return R.ok(service.savePolicy(policy));
    }

    @PostMapping("/policies/{id}/delete")
    @Operation(summary = "删除政策阶梯")
    public R<Void> removePolicy(@PathVariable String id) {
        service.removePolicy(id);
        return R.ok(null);
    }

    @GetMapping("/budgets")
    @Operation(summary = "年度返利预算")
    public R<RebateBudget> budgets(@RequestParam int year) {
        return R.ok(service.getBudget(year));
    }

    @PostMapping("/budgets")
    @Operation(summary = "保存年度预算（仅总额时按季均分）")
    public R<RebateBudget> saveBudget(@RequestBody RebateBudget budget) {
        return R.ok(service.saveBudget(budget));
    }

    @GetMapping("/budget-summary")
    @Operation(summary = "季度预算额度/消耗/余额")
    public R<Map<String, Object>> budgetSummary(@RequestParam int year,
                                                @RequestParam String quarter) {
        return R.ok(service.budgetSummary(year, quarter));
    }

    // ---------- 计算 / 审批 / 执行（3.9.1~3.9.3） ----------

    @PostMapping("/calculate")
    @Operation(summary = "季度返利计算（超额累进 + 预算校验，生成 DRAFT 结算单）")
    public R<Map<String, Object>> calculate(@RequestBody Map<String, Object> body) {
        String customerId = body.get("customerId") == null ? null
                : String.valueOf(body.get("customerId"));
        String quarter = body.get("quarter") == null ? null
                : String.valueOf(body.get("quarter"));
        return R.ok(service.calculate(customerId, quarter));
    }

    @GetMapping("/settlements")
    @Operation(summary = "返利结算单分页")
    public R<Page<RebateSettlement>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String customerId,
                                          @RequestParam(required = false) String quarter,
                                          @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, customerId, quarter, status));
    }

    @GetMapping("/settlements/{id}")
    @Operation(summary = "结算单详情（分段明细 + 审批日志）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/settlements/{id}/submit")
    @Operation(summary = "提交审批（超预算须附说明与平衡方案，升级总监）")
    public R<RebateSettlement> submit(@PathVariable String id,
                                      @RequestBody Map<String, Object> body) {
        String overReason = body.get("overReason") == null ? null
                : String.valueOf(body.get("overReason"));
        String balancePlan = body.get("balancePlan") == null ? null
                : String.valueOf(body.get("balancePlan"));
        return R.ok(service.submit(id, overReason, balancePlan));
    }

    @PostMapping("/settlements/{id}/execute")
    @Operation(summary = "执行（OFFSET 冲抵应收 / CASH 现金兑现）并生成结算凭证")
    public R<Map<String, Object>> execute(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        String execType = body.get("execType") == null ? null
                : String.valueOf(body.get("execType"));
        LocalDate execDate = body.get("execDate") == null || String.valueOf(body.get("execDate")).isBlank()
                ? null : LocalDate.parse(String.valueOf(body.get("execDate")));
        String confirmBy = body.get("confirmBy") == null ? null
                : String.valueOf(body.get("confirmBy"));
        String confirmNote = body.get("confirmNote") == null ? null
                : String.valueOf(body.get("confirmNote"));
        return R.ok(service.execute(id, execType, execDate, confirmBy, confirmNote));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrPayCorrection;
import com.erp.entity.hr.HrPayAdjustment;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.service.hr.HrPayrollRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 薪资核算与 PayrollRun（payroll-calculation 10.5.3 核算页 + 10.5.4 审批页）。 */
@Tag(name = "薪资核算")
@RestController
@RequestMapping("/api/hr/payroll/runs")
@RequiredArgsConstructor
public class HrPayrollRunController {

    private final HrPayrollRunService runService;

    // ---------- 补扣项（10.5.3 内嵌录入） ----------

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "补扣项录入（核算期内可改，批次生成后锁）")
    public R<HrPayAdjustment> saveAdjustment(@RequestBody Map<String, Object> payload) {
        return R.ok(runService.saveAdjustment(payload));
    }

    @GetMapping("/adjustments")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "补扣项清单")
    public R<List<HrPayAdjustment>> adjustments(@RequestParam(required = false) String period,
                                                @RequestParam(required = false) String empId) {
        return R.ok(runService.adjustmentList(period, empId));
    }

    // ---------- 核算与批次 ----------

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "核算数据准备预览（考勤锁定/挂起/绩效/补扣项）")
    public R<Map<String, Object>> preview(@RequestParam String period) {
        return R.ok(runService.preview(period));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "核算并生成 PayrollRun（负数 L1/波动 L4 标记；挂起暂停）")
    public R<HrPayrollRun> generate(@RequestParam String period,
                                    @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(runService.generate(period, payload));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "提交审批（NEGATIVE 阻断；VOLATILE 须 confirmVolatile）")
    public R<HrPayrollRun> submit(@PathVariable String id,
                                  @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(runService.submit(id, payload));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "HR 经理审批（通过锁定+工资条推送；驳回可修正重提）")
    public R<HrPayrollRun> approve(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(runService.approve(id, payload));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "作废重建（明细不可改，BR-4.7-13；补扣项解锁）")
    public R<HrPayrollRun> voidRun(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(runService.voidRun(id, String.valueOf(payload.get("reason"))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "批次详情")
    public R<HrPayrollRun> runById(@PathVariable String id) {
        return R.ok(runService.runById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "批次清单")
    public R<List<HrPayrollRun>> runList(@RequestParam(required = false) String period,
                                         @RequestParam(required = false) String status) {
        return R.ok(runService.runList(period, status));
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "批次明细快照")
    public R<List<HrPayrollRunItem>> runItems(@PathVariable String id) {
        return R.ok(runService.runItems(id));
    }

    // ---------- 薪酬更正（L2） ----------

    @PostMapping("/corrections")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "薪酬更正申请（原批次锁定后调整）")
    public R<HrPayCorrection> submitCorrection(@RequestBody Map<String, Object> payload) {
        return R.ok(runService.submitCorrection(payload));
    }

    @PostMapping("/corrections/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "更正 L2 审批（通过→生成关联原批次的更正批次）")
    public R<HrPayCorrection> approveCorrection(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(runService.approveCorrection(id, payload));
    }

    @GetMapping("/corrections")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "更正申请清单")
    public R<List<HrPayCorrection>> correctionList(@RequestParam(required = false) String period,
                                                   @RequestParam(required = false) String runId) {
        return R.ok(runService.correctionList(period, runId));
    }

    // ---------- 工资条（HR 侧异议回复） ----------

    @PostMapping("/payslips/{id}/reply")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "回复工资条异议（3 工作日，留痕）")
    public R<Map<String, Object>> replyDispute(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(runService.replyDispute(id, String.valueOf(payload.get("content"))));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrCompTimeBatch;
import com.erp.entity.hr.HrOvertimeApply;
import com.erp.entity.hr.HrOvertimeConfirm;
import com.erp.schedule.HrAttendanceScanTask;
import com.erp.service.hr.HrCompTimeService;
import com.erp.service.hr.HrOvertimeConfirmService;
import com.erp.service.hr.HrOvertimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 加班调休（M10-4-1/2/3，overtime-comp-time）：申请/确认分流/调休额度。 */
@Tag(name = "加班调休")
@RestController
@RequestMapping("/api/hr/overtime")
@RequiredArgsConstructor
public class HrOvertimeController {

    private final HrOvertimeService overtimeService;
    private final HrOvertimeConfirmService confirmService;
    private final HrCompTimeService compTimeService;

    // ---------- 加班申请 ----------

    @PostMapping("/applies")
    @Operation(summary = "提交加班申请（36h L1 拦截/紧急补单窗口）")
    public R<HrOvertimeApply> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(overtimeService.submit(payload));
    }

    @PostMapping("/applies/{id}/approve")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN','GM')")
    @Operation(summary = "主管审批（通过/驳回；GM_SPECIAL 单服务层限 ROLE_GM）")
    public R<HrOvertimeApply> approve(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(overtimeService.approve(id, payload));
    }

    @GetMapping("/applies/{id}/approval-view")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN','GM')")
    @Operation(summary = "审批视图（当月累计+部门预算占用率+特批超限原因）")
    public R<Map<String, Object>> approvalView(@PathVariable String id) {
        return R.ok(overtimeService.approvalView(id));
    }

    @GetMapping("/applies")
    @Operation(summary = "申请清单（status 可传 GM_SPECIAL 过滤 GM 待办）")
    public R<List<HrOvertimeApply>> applies(@RequestParam(required = false) String empId,
                                            @RequestParam(required = false) String status) {
        return R.ok(overtimeService.list(empId, status));
    }

    @GetMapping("/approval-reminders")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN','GM')")
    @Operation(summary = "加班审批超时催办清单（FR-4.7-6-2）")
    public R<List<com.erp.entity.hr.HrRemind>> approvalReminders() {
        return R.ok(overtimeService.approvalReminders());
    }

    @PostMapping("/approval-remind-scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "审批超时催办扫描（与 02:30 定时同逻辑，幂等）")
    public R<Map<String, Object>> approvalRemindScan() {
        return R.ok(Map.of("reminded", overtimeService.approvalRemindScan()));
    }

    // ---------- 加班确认与分流 ----------

    @PostMapping("/confirms/scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "确认扫描（MIN(申请,打卡)/EXCESS/倍数消费假日日历）")
    public R<Map<String, Object>> confirmScan() {
        int n = confirmService.confirmScan();
        return R.ok(Map.of("created", n));
    }

    @PostMapping("/confirms/{id}/resolve-excess")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "追认超时（按申请或打卡时长）")
    public R<HrOvertimeConfirm> resolveExcess(@PathVariable String id,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(confirmService.resolveExcess(id, payload));
    }

    @PostMapping("/confirms/{id}/split")
    @Operation(summary = "分流（法定假强制工资/周末默认调休/金额固化）")
    public R<HrOvertimeConfirm> split(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(confirmService.split(id, payload));
    }

    @GetMapping("/confirms/{id}/trial")
    @Operation(summary = "分流实时试算（工资 vs 调休，不落库）")
    public R<Map<String, Object>> trial(@PathVariable String id) {
        return R.ok(confirmService.trial(id));
    }

    @GetMapping("/confirms")
    @Operation(summary = "确认清单")
    public R<List<HrOvertimeConfirm>> confirms(@RequestParam(required = false) String empId,
                                               @RequestParam(required = false) String status) {
        return R.ok(confirmService.list(empId, status));
    }

    // ---------- 调休额度 ----------

    @GetMapping("/comp-time/batches")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "调休批次清单（FEFO 序）")
    public R<List<HrCompTimeBatch>> compTimeBatches(@RequestParam(required = false) String empId) {
        return R.ok(compTimeService.list(empId));
    }

    @GetMapping("/comp-time/balance")
    @Operation(summary = "我的调休余额（全角色自助）")
    public R<Map<String, Object>> compTimeBalance(@RequestParam(required = false) String empId) {
        return R.ok(compTimeService.balance(empId));
    }

    @PostMapping("/comp-time/expiry-scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "到期扫描（提醒+自动清零留痕）")
    public R<Map<String, Object>> expiryScan() {
        return R.ok(compTimeService.expiryScan());
    }

    @GetMapping("/comp-time/resign-settlement")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "离职折算登记留桩（2x 小时工资）")
    public R<Map<String, Object>> resignSettlement(@RequestParam String empId) {
        return R.ok(compTimeService.resignSettlement(empId));
    }
}

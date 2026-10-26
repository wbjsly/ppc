package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrLeaveApply;
import com.erp.entity.hr.HrLeaveType;
import com.erp.service.hr.HrLeaveApplyService;
import com.erp.service.hr.HrLeaveBalanceService;
import com.erp.service.hr.HrLeaveTypeService;
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

/** 请假与假期余额池（M10.4.4，leave-management）：类型字典/余额池/请假单。 */
@Tag(name = "请假管理")
@RestController
@RequestMapping("/api/hr/leave")
@RequiredArgsConstructor
public class HrLeaveController {

    private final HrLeaveTypeService typeService;
    private final HrLeaveBalanceService balanceService;
    private final HrLeaveApplyService applyService;

    // ---------- 假期类型字典 ----------

    @GetMapping("/types")
    @Operation(summary = "启用假期类型列表（申请表单驱动）")
    public R<List<HrLeaveType>> types() {
        return R.ok(typeService.list());
    }

    @PutMapping("/types/{typeCode}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "更新类型规则（扣减系数/法定天数/计薪标志）")
    public R<HrLeaveType> updateType(@PathVariable String typeCode,
                                     @RequestBody Map<String, Object> payload) {
        return R.ok(typeService.update(typeCode, payload));
    }

    // ---------- 余额池 ----------

    @GetMapping("/balances")
    @Operation(summary = "我的假期余额（按类型分组，全角色自助）")
    public R<Map<String, Object>> balances(@RequestParam(required = false) String empId) {
        return R.ok(balanceService.balances(empId));
    }

    @PostMapping("/balances/annual-grant-scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "年假年度发放扫描（工龄阶梯，同年幂等）")
    public R<Map<String, Object>> annualGrantScan() {
        return R.ok(balanceService.annualGrantScan());
    }

    @PostMapping("/balances/annual-expire-scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "年假当年清零扫描（留痕）")
    public R<Map<String, Object>> annualExpireScan() {
        return R.ok(balanceService.annualExpireScan());
    }

    // ---------- 请假单 ----------

    @PostMapping("/applies")
    @Operation(summary = "提交请假（余额类 L1 校验/法定上限/病假系数）")
    public R<HrLeaveApply> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(applyService.submit(payload));
    }

    @PostMapping("/applies/{id}/approve")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "审批（通过→FEFO 扣减+扣款固化；驳回）")
    public R<HrLeaveApply> approve(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(applyService.approve(id, payload));
    }

    @PostMapping("/applies/{id}/cancel")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "取消（更正驳回：扣减回滚+占用释放留痕）")
    public R<HrLeaveApply> cancel(@PathVariable String id, @RequestParam String reason) {
        return R.ok(applyService.cancel(id, reason));
    }

    @GetMapping("/applies")
    @Operation(summary = "请假清单")
    public R<List<HrLeaveApply>> applies(@RequestParam(required = false) String empId,
                                         @RequestParam(required = false) String status) {
        return R.ok(applyService.list(empId, status));
    }
}

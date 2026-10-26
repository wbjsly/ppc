package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrIitSpecialDeduction;
import com.erp.service.hr.HrIitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 员工自助端薪酬接口（/api/my/** 认证即可；专项附加扣除维护 + 电子工资条，payroll-calculation）。 */
@Tag(name = "我的薪酬")
@RestController
@RequestMapping("/api/my/payroll")
@RequiredArgsConstructor
public class HrMyPayrollController {

    private final HrIitService iitService;
    private final HrEmployeeDao empDao;
    private final com.erp.service.hr.HrPayrollRunService payrollRunService;

    @GetMapping("/special-deductions")
    @Operation(summary = "我的专项附加扣除（税务系统同步留桩，本期手工维护）")
    public R<List<HrIitSpecialDeduction>> mySpecial() {
        return R.ok(iitService.specialDeductions(myEmpId()));
    }

    @PostMapping("/special-deductions")
    @Operation(summary = "维护我的专项附加扣除")
    public R<HrIitSpecialDeduction> saveMySpecial(@RequestBody Map<String, Object> payload) {
        payload.put("empId", myEmpId());
        return R.ok(iitService.saveSpecialDeduction(payload));
    }

    @GetMapping("/payslips")
    @Operation(summary = "我的电子工资条（审批通过批次，只读快照）")
    public R<List<Map<String, Object>>> myPayslips() {
        return R.ok(payrollRunService.myPayslips(myEmpId()));
    }

    @PostMapping("/payslips/{id}/confirm")
    @Operation(summary = "确认工资条（非强制，留痕）")
    public R<Map<String, Object>> confirmPayslip(@org.springframework.web.bind.annotation.PathVariable String id) {
        return R.ok(payrollRunService.confirmPayslip(id, myEmpId()));
    }

    @PostMapping("/payslips/{id}/dispute")
    @Operation(summary = "工资条异议（核算员 3 工作日回复）")
    public R<Map<String, Object>> disputePayslip(@org.springframework.web.bind.annotation.PathVariable String id,
                                                 @RequestBody Map<String, Object> payload) {
        return R.ok(payrollRunService.disputePayslip(id, myEmpId(),
                String.valueOf(payload.get("content"))));
    }

    /** 按当前登录 USER_ID 反查员工 ID。 */
    private String myEmpId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        HrEmployee e = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getUserId, username)
                .eq(HrEmployee::getDelFlag, "0"));
        if (e == null) {
            throw new ServiceException(404, "当前账号未关联员工档案");
        }
        return e.getId();
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrIitSpecialDeduction;
import com.erp.entity.hr.HrIitTaxTable;
import com.erp.service.hr.HrIitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 个税计算（payroll-calculation 10.5.2：税率表/累计预扣/专项附加/年终奖）。 */
@Tag(name = "个税计算")
@RestController
@RequestMapping("/api/hr/payroll/iit")
@RequiredArgsConstructor
public class HrIitController {

    private final HrIitService iitService;

    @GetMapping("/tax-table")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "月度税率表（官方七级，YEAR 维度）")
    public R<List<HrIitTaxTable>> taxTable(@RequestParam int year) {
        return R.ok(iitService.taxTable(year));
    }

    @GetMapping("/cumulative")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "人×年累计预扣台账")
    public R<Map<String, Object>> cumulative(@RequestParam String empId, @RequestParam int year) {
        return R.ok(iitService.cumulative(empId, year));
    }

    @PostMapping("/carry-over")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "年度期初结转（上年累计写入）")
    public R<Map<String, Object>> carryOver(@RequestBody Map<String, Object> payload) {
        iitService.carryOver(String.valueOf(payload.get("empId")),
                Integer.parseInt(String.valueOf(payload.get("year"))),
                amt(payload.get("cumGross")), amt(payload.get("cumDeduct")),
                amt(payload.get("cumSpecial")), amt(payload.get("cumTaxPaid")));
        return R.ok(Map.of("ok", true));
    }

    @GetMapping("/special-deductions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "专项附加扣除清单（HR 侧查询）")
    public R<List<HrIitSpecialDeduction>> specialDeductions(@RequestParam(required = false) String empId) {
        return R.ok(iitService.specialDeductions(empId));
    }

    @PostMapping("/special-deductions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "维护专项附加扣除（upsert）")
    public R<HrIitSpecialDeduction> saveSpecial(@RequestBody Map<String, Object> payload) {
        return R.ok(iitService.saveSpecialDeduction(payload));
    }

    @PostMapping("/bonus-calc")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "年终奖计税试算（SINGLE 单独/COMBINED 并入，不落库）")
    public R<Map<String, Object>> bonusCalc(@RequestBody Map<String, Object> payload) {
        return R.ok(iitService.bonusTax(String.valueOf(payload.get("empId")),
                amt(payload.get("bonus")), str(payload.get("choice"))));
    }

    // ---------- 员工自助端见 HrMyPayrollController（/api/my/** 独立前缀，认证即可） ----------

    private static BigDecimal amt(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : new BigDecimal(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

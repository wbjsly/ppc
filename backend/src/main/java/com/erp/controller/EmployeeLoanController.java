package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.EmployeeLoanService;
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
 * 员工借款（M8-8-2/8.8-3，spec expense-reimbursement）：申请（超期加签）/ 查询 / 账龄视图。
 */
@Tag(name = "员工借款")
@RestController
@RequestMapping("/api/fin/employee-loans")
public class EmployeeLoanController {

    private final EmployeeLoanService service;

    public EmployeeLoanController(EmployeeLoanService service) {
        this.service = service;
    }

    @Operation(summary = "提交借款申请（超期>90天自动加签财务主管，C-4.6-14）")
    @PostMapping
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        return R.ok(service.apply(payload));
    }

    @Operation(summary = "借款单列表（员工/状态过滤）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String employeeId,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.list(employeeId, status));
    }

    @Operation(summary = "借款单详情（含账龄）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "账龄监控视图（按员工聚合，>90天红标 BR-4.6-02）")
    @GetMapping("/aging-view")
    public R<List<Map<String, Object>>> agingView() {
        return R.ok(service.agingView());
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.ExpenseReimburseService;
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
 * 费用报销（M8-8-1，spec expense-reimbursement）：提交（发票重复 L1）/ 查询 / 差额结算。
 */
@Tag(name = "费用报销")
@RestController
@RequestMapping("/api/fin/reimburse")
public class ExpenseReimburseController {

    private final ExpenseReimburseService service;
    private final com.erp.service.fin.ExpenseTypeService typeService;

    public ExpenseReimburseController(ExpenseReimburseService service,
                                      com.erp.service.fin.ExpenseTypeService typeService) {
        this.service = service;
        this.typeService = typeService;
    }

    @Operation(summary = "费用类型映射列表（含停用，8.8.1 维护抽屉）")
    @GetMapping("expense-types")
    public R<java.util.List<Map<String, Object>>> listTypes() {
        return R.ok(typeService.list());
    }

    @Operation(summary = "新增/修改费用类型映射（类型编码唯一；科目须 DR 方向）")
    @PostMapping("expense-types")
    public R<Map<String, Object>> saveType(@RequestBody Map<String, Object> payload) {
        return R.ok(typeService.save(payload));
    }

    @Operation(summary = "启用/停用费用类型（停用后新报销不可引用，历史不受影响）")
    @PostMapping("expense-types/{id}/status")
    public R<Void> typeStatus(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        typeService.updateStatus(id, String.valueOf(payload.get("status")));
        return R.ok(null);
    }

    @Operation(summary = "提交报销单（头+行+两节点审批；发票重复 L1 展示原单号）")
    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(service.submit(payload));
    }

    @Operation(summary = "报销单列表（状态/员工过滤）")
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String employeeId) {
        return R.ok(service.list(status, employeeId));
    }

    @Operation(summary = "报销单详情（行+冲销明细+结算信息）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "待结算差额计算")
    @GetMapping("/{id}/settle-info")
    public R<Map<String, Object>> settleInfo(@PathVariable String id) {
        return R.ok(service.settleInfo(id));
    }

    @Operation(summary = "出纳补付差额（收付中心 OUT，余额不足 422）")
    @PostMapping("/{id}/pay-difference")
    public R<Map<String, Object>> payDifference(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.payDifference(id, payload == null ? Map.of() : payload));
    }

    @Operation(summary = "登记员工退款（收付中心 IN，回写借款余额）")
    @PostMapping("/{id}/refund")
    public R<Map<String, Object>> refund(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(service.refund(id, payload));
    }
}

package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvFreeze;
import com.erp.service.inv.FreezeService;
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
 * 冻结/解冻管理（spec freeze-management，4.3.2 冻结库存）。
 * 查询仅需认证；发起/解冻权限在服务层分流（接口管可为，菜单管可见）；
 * 审批签署复用 /api/qms/approvals（底座 ROLE_REQUIRED 校验，C-4.4-05）。
 */
@Tag(name = "库存冻结")
@RestController
@RequestMapping("/api/inv/freeze")
public class FreezeController {

    private final FreezeService service;

    public FreezeController(FreezeService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "发起冻结（质量/财务单笔唯一，挂单节点审批）",
            description = "质量 ROLE_QUALITY_ENG 发起、财务 ROLE_FINANCE 发起（FR-4.4-5-1）")
    public R<InvFreeze> apply(@RequestBody InvFreeze req) {
        return R.ok(service.apply(req));
    }

    @PostMapping("/{id}/unfreeze")
    @Operation(summary = "发起解冻（仅原发起人，处理结果与依据必填，独立审批 FR-4.4-5-6/7）")
    public R<InvFreeze> unfreeze(@PathVariable String id, @RequestBody InvFreeze body) {
        return R.ok(service.applyUnfreeze(id, body.getReleaseResult(), body.getReleaseBasis()));
    }

    @GetMapping("/estimate")
    @Operation(summary = "影响评估预估（FR-4.4-5-3 异常列）",
            description = "scope=ALL 或 冻结量 ≥ FREEZE_IMPACT_RATIO%×可用量 时 needConfirm=true；维度不存在 422")
    public R<Map<String, Object>> estimate(@RequestParam String warehouseCode,
                                           @RequestParam String itemCode,
                                           @RequestParam(required = false) String batchNo,
                                           @RequestParam(required = false, defaultValue = "BATCH") String scope,
                                           @RequestParam(required = false) java.math.BigDecimal qty) {
        return R.ok(service.estimate(warehouseCode, itemCode, batchNo, scope, qty));
    }

    @GetMapping
    @Operation(summary = "冻结库存查询（类型/状态/物料/批次/关键字；NCR 来源只读）")
    public R<List<Map<String, Object>>> query(
            @RequestParam(required = false) String freezeType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.query(freezeType, status, itemCode, batchNo, keyword));
    }
}

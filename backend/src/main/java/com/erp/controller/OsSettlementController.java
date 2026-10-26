package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.proc.OsAp;
import com.erp.entity.proc.OsSettlement;
import com.erp.service.proc.OsSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 外协对账与应付挂账接口（change add-outsourcing-processing，spec outsourcing-settlement，菜单 5.10.4）。
 *
 * 路径前缀用 /api/mrp 而非 /api/proc：SecurityConfig 对 /api/proc/** 的 POST/PUT/DELETE 兜底
 * hasRole(ADMIN)，会把 PURCHASER/PLANNER 先拦成 403（borrow-approval 同类坑）；
 * /api/mrp 走 /api/** authenticated 兜底，角色校验在 service 层 requireRole。
 * 权限：读/写 = PLANNER/PLAN_MGR/PURCHASER/ADMIN。
 */
@Tag(name = "外协对账（5.10.4）")
@RestController
@RequestMapping("/api/mrp/os-settlements")
public class OsSettlementController {

    private final OsSettlementService service;

    public OsSettlementController(OsSettlementService service) {
        this.service = service;
    }

    @GetMapping("/list")
    @Operation(summary = "对账单列表", description = "PO 号/供应商/状态筛选，创建时间倒序")
    public R<List<OsSettlement>> list(@RequestParam(required = false) String poNo,
                                      @RequestParam(required = false) String supplierCode,
                                      @RequestParam(required = false) String status) {
        return R.ok(service.list(poNo, supplierCode, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "对账单详情", description = "头 + 行（行含收货行引用/数量/PO 单价快照/金额）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/generate")
    @Operation(summary = "按 PO 生成对账单（DRAFT）",
            description = "仅外协 PO（派工单回联识别）；汇总未对账收货过账行，金额=数量×PO 成交价；"
                    + "已引用行自动排除；无未对账明细 422")
    public R<OsSettlement> generate(@RequestBody Map<String, String> body) {
        return R.ok(service.generate(body.get("poNo")));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除草稿", description = "仅 DRAFT 可删；行删后收货行引用释放可重新汇总")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批（DRAFT → CONFIRMED）",
            description = "挂底座单节点单签（ROLE_PLAN_MGR）；无行 422")
    public R<OsSettlement> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @GetMapping("/ap/list")
    @Operation(summary = "应付挂账列表", description = "供应商/状态筛选（D-OS-02 最小 AP：仅 OPEN 状态与金额）")
    public R<List<OsAp>> listAp(@RequestParam(required = false) String supplierCode,
                                @RequestParam(required = false) String status) {
        return R.ok(service.listAp(supplierCode, status));
    }
}

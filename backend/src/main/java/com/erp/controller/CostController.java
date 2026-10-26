package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.OverheadPool;
import com.erp.service.mrp.CostAggregationService;
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
 * 成本归集接口（change add-cost-aggregation，spec production-cost-aggregation，菜单 5.11.1-3）。
 * 权限：写（重跑/池录入）= COST_ACCOUNTANT/ADMIN；读 = +PLANNER/PLAN_MGR；
 * 差异审阅 = PLAN_MGR/ADMIN——均在 service 层校验（spec 场景：计划线只读、越权 403）。
 * 无 /api/proc 前缀 → 不落 SecurityConfig ADMIN 兜底（5.10 教训）。
 */
@Tag(name = "成本归集（5.11.1-3）")
@RestController
@RequestMapping("/api/mrp/cost")
public class CostController {

    private final CostAggregationService service;

    // ---------- 完工凭证（5.11.4，spec completion-cost-voucher） ----------

    private final com.erp.service.mrp.CostVoucherService voucherService;

    public CostController(CostAggregationService service,
                          com.erp.service.mrp.CostVoucherService voucherService) {
        this.service = service;
        this.voucherService = voucherService;
    }

    @Operation(summary = "归集列表（keyword=工单号/物料，period=yyyyMM）")
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String period) {
        return R.ok(service.list(keyword, period));
    }

    @Operation(summary = "归集详情（summary + 明细行）")
    @GetMapping("/{moId}")
    public R<Map<String, Object>> detail(@PathVariable String moId) {
        return R.ok(service.detail(moId));
    }

    @Operation(summary = "手动重跑归集（重建式；不触碰已生成凭证）")
    @PostMapping("/{moId}/rerun")
    public R<Map<String, Object>> rerun(@PathVariable String moId) {
        return R.ok(service.rerun(moId));
    }

    @Operation(summary = "差异报告主管审阅（RAISED → REVIEWED）")
    @PostMapping("/{moId}/review")
    public R<Void> review(@PathVariable String moId) {
        service.reviewVariance(moId);
        return R.ok();
    }

    @Operation(summary = "制造费用池列表（期次倒序）")
    @GetMapping("/pools")
    public R<List<OverheadPool>> pools() {
        return R.ok(service.listPools());
    }

    @Operation(summary = "制造费用池录入/修改（期次唯一 upsert）")
    @PostMapping("/pools")
    public R<Map<String, Object>> savePool(@RequestBody Map<String, Object> body) {
        return R.ok(service.savePool(body));
    }

    @Operation(summary = "完工凭证列表（status/period 筛选；COST_ACCOUNTANT+财务只读）")
    @GetMapping("/vouchers")
    public R<List<Map<String, Object>>> vouchers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String period) {
        return R.ok(voucherService.list(status, period));
    }

    @Operation(summary = "待过账（DRAFT）完工凭证清单")
    @GetMapping("/vouchers/drafts")
    public R<List<Map<String, Object>>> voucherDrafts() {
        return R.ok(voucherService.draftList());
    }

    @Operation(summary = "完工凭证明细（头行）")
    @GetMapping("/vouchers/{voucherId}")
    public R<Map<String, Object>> voucherDetail(@PathVariable String voucherId) {
        return R.ok(voucherService.detail(voucherId));
    }

    @Operation(summary = "DRAFT 下期过账（COST_ACCOUNTANT/ADMIN）")
    @PostMapping("/vouchers/{voucherId}/post")
    public R<Void> postVoucher(@PathVariable String voucherId) {
        voucherService.postDraft(voucherId);
        return R.ok();
    }

    @Operation(summary = "月度差异转销（BR-4.5-29：净额 → 主营业务成本，同月幂等）")
    @PostMapping("/variance-transfer")
    public R<Map<String, Object>> transferVariance(@RequestParam String period) {
        com.erp.entity.fin.FinVoucher v = voucherService.transferVariance(period);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("transferred", v != null);
        out.put("voucherNo", v == null ? null : v.getVoucherNo());
        return R.ok(out);
    }
}

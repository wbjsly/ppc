package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamAssetChange;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.fin.GlVoucherService;
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
 * 折旧管理（add-asset-depreciation，spec asset-depreciation）。
 * 月度计提触发 / 应提预览 / 计提单与明细 / 变动处理（政策变更/减值）/ 冲回 / 凭证反查。
 */
@Tag(name = "折旧管理")
@RestController
@RequestMapping("/api/eam/depreciation")
public class EamDepreciationController {

    private final EamDepreciationService service;
    private final GlVoucherService voucherService;

    public EamDepreciationController(EamDepreciationService service,
                                     GlVoucherService voucherService) {
        this.service = service;
        this.voucherService = voucherService;
    }

    // ================= 7.2.1 月度计提 =================

    @Operation(summary = "执行本月计提（同事务：幂等卡控→应提清单→三方法→凭证→台账回写）")
    @PostMapping("/runs/{periodTag}/execute")
    public R<Map<String, Object>> runDepreciation(@PathVariable String periodTag) {
        return R.ok(service.runDepreciation(periodTag));
    }

    @Operation(summary = "应提预览（未落库：应提卡清单+预计折旧+缺失告警+残值预警）")
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam String periodTag) {
        return R.ok(service.preview(periodTag));
    }

    @Operation(summary = "计提单分页")
    @GetMapping("/runs")
    public R<Page<EamDepreciationRun>> pageRuns(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String periodTag) {
        return R.ok(service.page(current, size, periodTag));
    }

    @Operation(summary = "计提单详情（含明细行）")
    @GetMapping("/runs/{runId}")
    public R<Map<String, Object>> runDetail(@PathVariable String runId) {
        return R.ok(service.detail(runId));
    }

    @Operation(summary = "整单冲回（红冲凭证+明细标 REVERSED+台账回滚+放行重提）")
    @PostMapping("/runs/{runId}/reverse")
    public R<Map<String, Object>> reverse(@PathVariable String runId,
                                          @RequestParam(required = false) String reason) {
        return R.ok(service.reverse(runId, reason));
    }

    // ================= 7.2.2 变动处理 =================

    @Operation(summary = "变动单分页（POLICY/IMPAIRMENT/REVERSE）")
    @GetMapping("/changes")
    public R<Page<EamAssetChange>> pageChanges(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String changeType,
                                               @RequestParam(required = false) String cardNo) {
        return R.ok(service.pageChange(current, size, changeType, cardNo));
    }

    /** 政策变更入参（BR-4.13-14） */
    public record PolicyChangeReq(String cardId, Integer afterLifeYears,
                                  java.math.BigDecimal afterResidualRate,
                                  String afterMethod, String reason,
                                  String effectivePeriod) {
    }

    @Operation(summary = "政策变更提交（单签 ROLE_FIN_ASSET；生效期默认系统下期，不追溯）")
    @PostMapping("/changes/policy")
    public R<Map<String, Object>> changePolicy(@RequestBody PolicyChangeReq req) {
        return R.ok(service.changePolicy(req.cardId(), req.afterLifeYears(),
                req.afterResidualRate(), req.afterMethod(), req.reason(), req.effectivePeriod()));
    }

    /** 减值入参（BR-4.13-15） */
    public record ImpairmentReq(String cardId, java.math.BigDecimal amount, String reason) {
    }

    @Operation(summary = "减值提交（单签；通过出减值凭证+卡片 IMPAIRMENT 落值+次月重算）")
    @PostMapping("/changes/impairment")
    public R<Map<String, Object>> impairment(@RequestBody ImpairmentReq req) {
        return R.ok(service.impairment(req.cardId(), req.amount(), req.reason()));
    }

    @Operation(summary = "变动单审核通过（回调入口；幂等）")
    @PostMapping("/changes/{changeId}/approve")
    public R<Map<String, Object>> approveChange(@PathVariable String changeId) {
        return R.ok(service.approveChange(changeId));
    }

    @Operation(summary = "变动单驳回")
    @PostMapping("/changes/{changeId}/reject")
    public R<Map<String, Object>> rejectChange(@PathVariable String changeId,
                                               @RequestParam(required = false) String reason) {
        return R.ok(service.rejectChange(changeId, reason));
    }

    // ================= 7.2.3 凭证生成（反查） =================

    @Operation(summary = "折旧凭证反查（sourceType=DEPRECIATION，来源单号=run 单号）")
    @GetMapping("/vouchers")
    public R<List<Map<String, Object>>> vouchers(@RequestParam(required = false) String sourceDocNo) {
        return R.ok(voucherService.listBySource("DEPRECIATION", sourceDocNo));
    }

    @Operation(summary = "凭证明细（会计分录行：行号/科目/方向/金额/摘要）")
    @GetMapping("/vouchers/{voucherId}/details")
    public R<List<com.erp.entity.fin.FinVoucherLine>> detailsByVoucher(
            @PathVariable String voucherId) {
        return R.ok(service.detailsByVoucher(voucherId));
    }
}

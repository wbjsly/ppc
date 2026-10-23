package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamCountDiff;
import com.erp.service.eam.EamCountDiffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 盘点差异处理（add-asset-count，spec asset-count）。
 * 7.5.2 差异处理：自动比对/调查留痕/分级审批/责任认定/账务执行/冻结管理。
 */
@Tag(name = "盘点差异处理")
@RestController
@RequestMapping("/api/eam/count-diffs")
public class EamCountDiffController {

    private final EamCountDiffService service;

    public EamCountDiffController(EamCountDiffService service) {
        this.service = service;
    }

    @Operation(summary = "差异单分页")
    @GetMapping
    public R<Page<EamCountDiff>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String planId,
                                      @RequestParam(required = false) String diffType,
                                      @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, planId, diffType, status));
    }

    @Operation(summary = "差异单详情（含行与卡片）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "自动比对（实盘 vs 快照 → 四类差异生成，幂等）")
    @PostMapping("/compare/{planId}")
    public R<Map<String, Object>> autoCompare(@PathVariable String planId) {
        return R.ok(service.autoCompare(planId));
    }

    @Operation(summary = "差异原因调查留痕（查无原因升级财务经理）")
    @PostMapping("/{id}/investigate")
    public R<Map<String, Object>> investigate(@PathVariable String id,
                                              @RequestParam String reason,
                                              @RequestParam(required = false) String evidence) {
        return R.ok(service.investigate(id, reason, evidence));
    }

    @Operation(summary = "提交分级审批（阈值内两节点 / 超阈值四节点）")
    @PostMapping("/{id}/submit-approval")
    public R<Map<String, Object>> submitApproval(@PathVariable String id) {
        return R.ok(service.submitApproval(id));
    }

    @Operation(summary = "盘亏责任认定（NONE/COMPENSATE/INSURANCE 桩位，BR-28）")
    @PostMapping("/{id}/liability")
    public R<Map<String, Object>> liability(@PathVariable String id,
                                            @RequestParam String liabilityType,
                                            @RequestParam(required = false) String liabilityPerson,
                                            @RequestParam(required = false) BigDecimal compensateAmount,
                                            @RequestParam String basis) {
        return R.ok(service.liability(id, liabilityType, liabilityPerson, compensateAmount, basis));
    }

    @Operation(summary = "账务执行（盘盈建卡+凭证 / 盘亏核销+卡片 DISPOSED）")
    @PostMapping("/{id}/post-adjust")
    public R<Map<String, Object>> postAdjust(@PathVariable String id) {
        return R.ok(service.postAdjust(id));
    }

    @Operation(summary = "标签异常冻结/解冻（BR-30；diffId 空且 freeze=1 现场新建 TAG_ISSUE 差异单）")
    @PostMapping("/freeze")
    public R<Map<String, Object>> freezeTag(@RequestParam String cardId,
                                            @RequestParam(required = false) String diffId,
                                            @RequestParam(defaultValue = "true") boolean freeze) {
        return R.ok(service.freezeTag(cardId, diffId, freeze));
    }

    @Operation(summary = "冻结卡片列表（7.5.2 冻结管理）")
    @GetMapping("/frozen")
    public R<List<Map<String, Object>>> frozenCards() {
        return R.ok(service.frozenCards());
    }

    @Operation(summary = "补标解冻关闭（标签异常闭环）")
    @PostMapping("/{id}/close-tag-issue")
    public R<Map<String, Object>> closeTagIssue(@PathVariable String id) {
        return R.ok(service.closeTagIssue(id));
    }
}

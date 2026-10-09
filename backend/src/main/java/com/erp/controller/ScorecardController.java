package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.scm.ScmScorecardAppeal;
import com.erp.entity.scm.ScmScorecardModel;
import com.erp.entity.scm.ScmScorecardRectify;
import com.erp.entity.scm.ScmScorecardResult;
import com.erp.security.IntfGuard;
import com.erp.service.scm.ScorecardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 2.9.2 绩效评估（spec supplier-scorecard 4.9 流程七全链）。 */
@Tag(name = "供应商绩效记分卡")
@RestController
@RequestMapping("/api/scm/scorecard")
public class ScorecardController {

    private final ScorecardService service;

    public ScorecardController(ScorecardService service) {
        this.service = service;
    }

    // ---- 模型 ----

    @Operation(summary = "模型分页")
    @GetMapping("/models")
    public R<Page<ScmScorecardModel>> models(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.modelPage(current, size, status));
    }

    @Operation(summary = "保存模型（权重≠100% 422 硬阻断；维度指标须已注册口径）")
    @PostMapping("/models")
    public R<Map<String, Object>> saveModel(@RequestBody Map<String, Object> body) {
        return R.ok(service.saveModel(body));
    }

    // ---- 采集与结果 ----

    @Operation(summary = "月度采集计算（幂等：同月已算跳过；自动抽取无手工修改入口）")
    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestBody(required = false) Map<String, Object> body) {
        Object m = body == null ? null : body.get("monthTag");
        return R.ok(service.collectMonth(m == null ? null : String.valueOf(m)));
    }

    @Operation(summary = "记分卡分页")
    @GetMapping("/results")
    public R<Page<ScmScorecardResult>> results(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String monthTag,
                                               @RequestParam(required = false) String grade,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String supplierId) {
        return R.ok(service.resultPage(current, size, monthTag, grade, status, supplierId));
    }

    @Operation(summary = "记分卡详情（数据穿透 + 环比）")
    @GetMapping("/results/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "审核（高危：仅 ADMIN；意见必填）")
    @PostMapping("/results/{id}/review")
    public R<Map<String, Object>> review(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("记分卡审核");
        Object op = body.get("opinion");
        return R.ok(service.review(id, op == null ? null : String.valueOf(op)));
    }

    @Operation(summary = "公示（高危：仅 ADMIN；未审核 422 留痕 / C-4.9-09）")
    @PostMapping("/results/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable String id) {
        IntfGuard.requireAdmin("记分卡公示");
        return R.ok(service.publish(id));
    }

    // ---- 整改与冻结 ----

    @Operation(summary = "整改单分页")
    @GetMapping("/rectifies")
    public R<Page<ScmScorecardRectify>> rectifies(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String status) {
        return R.ok(service.rectifyPage(current, size, status));
    }

    @Operation(summary = "整改关闭")
    @PostMapping("/rectifies/{id}/close")
    public R<Map<String, Object>> close(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Object note = body == null ? null : body.get("note");
        return R.ok(service.closeRectify(id, note == null ? "整改项全部关闭" : String.valueOf(note)));
    }

    @Operation(summary = "解冻（高危：仅 ADMIN；审批人须 ≠ 整改关闭人）")
    @PostMapping("/rectifies/{id}/unfreeze")
    public R<Map<String, Object>> unfreeze(@PathVariable String id) {
        IntfGuard.requireAdmin("冻结解冻审批");
        return R.ok(service.unfreeze(id));
    }

    // ---- 申诉复核 ----

    @Operation(summary = "申诉复核（成立→生成修正版并重新公示，原版保留）")
    @PostMapping("/appeals/{id}/review")
    public R<Map<String, Object>> reviewAppeal(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("申诉复核");
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        Object c = body.get("conclusion");
        return R.ok(service.reviewAppeal(id, confirmed, c == null ? null : String.valueOf(c)));
    }
}

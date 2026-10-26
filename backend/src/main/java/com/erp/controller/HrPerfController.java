package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrPerfPlan;
import com.erp.entity.hr.HrPerfResult;
import com.erp.entity.hr.HrPerfScore;
import com.erp.service.hr.HrPerfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 绩效管理（performance-management，菜单 10.10 四叶）。 */
@Tag(name = "绩效管理")
@RestController
@RequestMapping("/api/hr/performance")
@RequiredArgsConstructor
public class HrPerfController {

    private final HrPerfService perfService;

    // ---------- 绩效方案（10.10.1） ----------

    @PostMapping("/plans")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "创建方案（同周期同部门唯一生效，评分期内系数锁定）")
    public R<HrPerfPlan> createPlan(@RequestBody Map<String, Object> payload) {
        return R.ok(perfService.createPlan(payload));
    }

    @PostMapping("/plans/{id}/void")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "作废方案（已有评分不可作废）")
    public R<HrPerfPlan> voidPlan(@PathVariable String id) {
        return R.ok(perfService.voidPlan(id));
    }

    @GetMapping("/plans")
    @Operation(summary = "方案清单")
    public R<List<HrPerfPlan>> planList(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) String deptId,
                                        @RequestParam(required = false) String status) {
        return R.ok(perfService.planList(period, deptId, status));
    }

    // ---------- 评分录入（10.10.2） ----------

    @PostMapping("/scores")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "录入评分（分值→等级自动映射）")
    public R<HrPerfScore> enterScore(@RequestBody Map<String, Object> payload) {
        return R.ok(perfService.enterScore(payload));
    }

    @PostMapping("/scores/{id}/submit")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "提交评分 DRAFT→SUBMITTED")
    public R<HrPerfScore> submitScore(@PathVariable String id) {
        return R.ok(perfService.submitScore(id));
    }

    @PostMapping("/scores/batch-submit")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "批量提交评分")
    public R<Map<String, Object>> batchSubmit(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("scoreIds");
        return R.ok(perfService.batchSubmit(ids));
    }

    @GetMapping("/scores")
    @Operation(summary = "评分清单")
    public R<List<HrPerfScore>> scoreList(@RequestParam(required = false) String period,
                                          @RequestParam(required = false) String planId,
                                          @RequestParam(required = false) String empId,
                                          @RequestParam(required = false) String status) {
        return R.ok(perfService.scoreList(period, planId, empId, status));
    }

    // ---------- 评分校准（10.10.3，自建状态机批量端点） ----------

    @PostMapping("/scores/calibrate")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "批量校准（CALIBRATE/REJECT，等级调整留痕）")
    public R<Map<String, Object>> calibrate(@RequestBody Map<String, Object> payload) {
        return R.ok(perfService.calibrate(payload));
    }

    @PostMapping("/scores/confirm")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "批量确认（生成结果快照）")
    public R<Map<String, Object>> confirm(@RequestBody Map<String, Object> payload) {
        return R.ok(perfService.confirm(payload));
    }

    @GetMapping("/scores/{id}/logs")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "校准日志")
    public R<List<Map<String, Object>>> logs(@PathVariable String id) {
        return R.ok(perfService.calibrateLogs(id));
    }

    // ---------- 绩效结果（10.10.4，薪酬取数来源） ----------

    @GetMapping("/results")
    @Operation(summary = "结果快照清单（薪酬核算取数）")
    public R<List<HrPerfResult>> results(@RequestParam(required = false) String period,
                                         @RequestParam(required = false) String deptId) {
        return R.ok(perfService.resultList(period, deptId));
    }
}

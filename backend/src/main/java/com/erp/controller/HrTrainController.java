package com.erp.controller;

import com.erp.common.R;
import com.erp.service.hr.HrTrainPlanService;
import com.erp.service.hr.HrTrainSickScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 培训计划 HR 侧（training-plan-execution 10.9.1 + attendance-core 门禁处置）。 */
@Tag(name = "培训计划")
@RestController
@RequestMapping("/api/hr/train")
@RequiredArgsConstructor
public class HrTrainController {

    private final HrTrainPlanService planService;
    private final HrTrainSickScheduleService sickScheduleService;

    // ---- 计划 ----

    @GetMapping("/plans")
    @Operation(summary = "计划清单（年度过滤，含完成率统计）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Integer year) {
        return R.ok(planService.list(year));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新建计划（编制即发布，自动展开参训任务）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(planService.create(payload));
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "修改计划（变更留痕；对象变更只补新任务）")
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(planService.update(id, payload));
    }

    @PostMapping("/plans/{id}/cancel")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "作废计划（未完成任务连带取消；已生成凭证红字冲销）")
    public R<Void> cancel(@PathVariable String id) {
        planService.cancel(id);
        return R.ok(null);
    }

    @GetMapping("/plans/{id}")
    @Operation(summary = "计划详情（任务明细 + 完成率 + 凭证）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(planService.detail(id));
    }

    @PostMapping("/tasks/{id}/result")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "结果登记（成绩≥合格线 COMPLETED 否则 FAILED；留痕；矩阵联动）")
    public R<Map<String, Object>> registerResult(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(planService.registerResult(id, payload));
    }

    @PostMapping("/plans/{id}/cost")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "实际费用登记（自动生成计提凭证，TRAINING 锚点幂等）")
    public R<Map<String, Object>> registerCost(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(planService.registerCost(id, payload));
    }

    @GetMapping("/rates")
    @Operation(summary = "完成率（部门/年度维度，CANCELLED 不计分母）")
    public R<List<Map<String, Object>>> rates(@RequestParam(required = false) Integer year,
                                              @RequestParam(required = false) String deptId) {
        return R.ok(planService.rates(year, deptId));
    }

    @PostMapping("/remind-scan")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "手动触发催办扫描（通常由每日任务执行，幂等）")
    public R<Map<String, Object>> remindScan() {
        return R.ok(planService.remindScan());
    }

    // ---- 带病排班（attendance-core 门禁存量处置） ----

    @GetMapping("/sick-schedules")
    @Operation(summary = "带病排班清单（认证失效员工的未来排班，HR 与本部门主管可处置）")
    public R<List<Map<String, Object>>> sickSchedules() {
        return R.ok(sickScheduleService.list());
    }

    @PostMapping("/sick-schedules/{scheduleId}/dispose")
    @Operation(summary = "处置（CANCEL=取消排班 / REASSIGN=改派他人，留痕）")
    public R<Map<String, Object>> dispose(@PathVariable String scheduleId, @RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.get("action"));
        String newEmpId = body.get("newEmpId") == null ? null : String.valueOf(body.get("newEmpId"));
        return R.ok(sickScheduleService.dispose(scheduleId, action, newEmpId));
    }
}

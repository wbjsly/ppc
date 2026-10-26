package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmTimesheetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

/**
 * 工时填报与审核（add-timesheet-management，FR-4.8-4-1/2；/my 自助 + 11.5.2）。
 * 补录审批走通用底座 /api/qms/approvals/*（BIZ_TYPE=PmBackfill，单节点 PMO）。
 */
@Tag(name = "工时填报")
@RestController
@RequestMapping("/api/my/timesheets")
@RequiredArgsConstructor
public class PmTimesheetController {

    private final PmTimesheetService timesheetService;

    @GetMapping("/today")
    @Operation(summary = "今日填报状态（/my 页头部）")
    public R<Map<String, Object>> myToday() {
        return R.ok(timesheetService.myToday());
    }

    @GetMapping
    @Operation(summary = "工时单列表（默认本人；PM/PMO/ADMIN/DEPT_HEAD 可查他人）")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String empId,
                                             @RequestParam(required = false) String dateFrom,
                                             @RequestParam(required = false) String dateTo) {
        return R.ok(timesheetService.list(empId, dateFrom, dateTo));
    }

    @PostMapping
    @Operation(summary = "保存工时单（0.5h 步长/24h 合计/任务级校验/同人同天一单）")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(timesheetService.save(payload));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交（三道 L1 + 补录分档：超 2 周自动 PMO 审批，更早 422）")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(timesheetService.submit(id));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "审核（服务层校验单据级审核人=任务负责人/PMO/ADMIN/DEPT_HEAD；驳回必填原因）")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(timesheetService.review(id, payload));
    }

    @PostMapping("/bulk-approve")
    @Operation(summary = "批量通过（逐单审核人校验，服务层把关）")
    public R<Map<String, Object>> bulkApprove(@RequestBody List<String> ids) {
        return R.ok(timesheetService.bulkApprove(ids));
    }

    @GetMapping("/pending-review")
    @Operation(summary = "待审队列（审核人视角；管理角色看全量，服务层过滤）")
    public R<List<Map<String, Object>>> pendingReview() {
        return R.ok(timesheetService.pendingReview());
    }
}

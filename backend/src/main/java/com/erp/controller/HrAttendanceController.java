package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrAttendanceException;
import com.erp.entity.hr.HrMakeupPunch;
import com.erp.entity.hr.HrPunchRecord;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import com.erp.schedule.HrAttendanceScanTask;
import com.erp.service.hr.HrAttendanceService;
import com.erp.service.hr.HrMakeupPunchService;
import com.erp.service.hr.HrPunchService;
import com.erp.service.hr.HrShiftService;
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

/** 考勤管理（M10-3，attendance-core）：排班/打卡/异常状态机/补卡/汇总。 */
@Tag(name = "考勤管理")
@RestController
@RequestMapping("/api/hr/attendance")
@RequiredArgsConstructor
public class HrAttendanceController {

    private final HrShiftService shiftService;
    private final HrPunchService punchService;
    private final HrAttendanceService attendanceService;
    private final HrMakeupPunchService makeupService;
    private final HrAttendanceScanTask scanTask;

    // ---------- 班次与排班（自助/管理共用 /api/hr/attendance 前缀） ----------

    @GetMapping("/shifts")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "班次列表")
    public R<List<HrShift>> shifts() {
        return R.ok(shiftService.list());
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新增班次（跨夜工时自动计算）")
    public R<HrShift> createShift(@RequestBody Map<String, Object> payload) {
        return R.ok(shiftService.create(payload));
    }

    @PutMapping("/shifts/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "更新班次")
    public R<HrShift> updateShift(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(shiftService.update(id, payload));
    }

    @PutMapping("/shifts/{id}/status")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "停用/启用班次")
    public R<HrShift> toggleShift(@PathVariable String id, @RequestParam String status) {
        return R.ok(shiftService.toggleStatus(id, status));
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "单条排班（同人同日重排以最后一次为准 L4）")
    public R<HrSchedule> schedule(@RequestBody Map<String, Object> payload) {
        return R.ok(shiftService.schedule(payload));
    }

    @PostMapping("/schedules/bulk")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门批量默认班次")
    public R<Map<String, Object>> bulkSchedule(@RequestBody Map<String, Object> payload) {
        return R.ok(shiftService.bulkSchedule(payload));
    }

    @PostMapping("/schedules/copy")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "复制上月排班（同日冲突 L4 覆盖留痕）")
    public R<Map<String, Object>> copyMonth(@RequestParam String from, @RequestParam String to) {
        return R.ok(shiftService.copyMonth(from, to));
    }

    @GetMapping("/schedules")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门月度排班矩阵")
    public R<List<Map<String, Object>>> deptMatrix(@RequestParam(required = false) String deptId,
                                                   @RequestParam String period) {
        return R.ok(shiftService.deptMonthMatrix(deptId, period));
    }

    @GetMapping("/schedules/my")
    @Operation(summary = "我的月度排班（全角色自助）")
    public R<List<HrSchedule>> mySchedules(@RequestParam(required = false) String empId,
                                           @RequestParam String period) {
        return R.ok(shiftService.monthSchedules(empId, period));
    }

    // ---------- 打卡（自助三通道） ----------

    @PostMapping("/punches/web")
    @Operation(summary = "网页今日打卡（同向重复拦截）")
    public R<HrPunchRecord> webPunch(@RequestBody Map<String, Object> payload) {
        return R.ok(punchService.webPunch(payload));
    }

    @PostMapping("/punches/gps")
    @Operation(summary = "H5 定位打卡（超阈值 SUSPICIOUS）")
    public R<HrPunchRecord> gpsPunch(@RequestBody Map<String, Object> payload) {
        return R.ok(punchService.gpsPunch(payload));
    }

    @PostMapping("/punches/import")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "CSV 批量导入（工号关联/去重/报错清单）")
    public R<Map<String, Object>> importCsv(@RequestBody List<Map<String, Object>> rows) {
        return R.ok(punchService.importCsv(rows));
    }

    @GetMapping("/punches/my")
    @Operation(summary = "我的打卡记录（全角色自助）")
    public R<List<HrPunchRecord>> myPunches(@RequestParam(required = false) String empId,
                                            @RequestParam String period) {
        return R.ok(punchService.myRecords(empId, period));
    }

    // ---------- 异常状态机 ----------

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "异常清单（按状态/员工/日期）")
    public R<List<HrAttendanceException>> exceptions(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String empId,
                                                     @RequestParam(required = false) String fromDate,
                                                     @RequestParam(required = false) String toDate) {
        return R.ok(attendanceService.list(status, empId, fromDate, toDate));
    }

    @GetMapping("/exceptions/my")
    @Operation(summary = "我的异常（全角色自助）")
    public R<List<HrAttendanceException>> myExceptions(@RequestParam(required = false) String empId,
                                                       @RequestParam String period) {
        return R.ok(attendanceService.myExceptions(empId, period));
    }

    @PostMapping("/exceptions/appeal")
    @Operation(summary = "批量申诉（5 工作日期限拦截）")
    public R<Map<String, Object>> batchAppeal(@RequestBody Map<String, Object> payload) {
        return R.ok(attendanceService.batchAppeal(payload));
    }

    @PostMapping("/exceptions/return")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "佐证无效退回")
    public R<Map<String, Object>> returnAppeal(@RequestBody Map<String, Object> payload) {
        return R.ok(attendanceService.returnAppeal(payload));
    }

    @PostMapping("/exceptions/mgr-confirm")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "批量主管确认（无主管 HR 代确认留痕）")
    public R<Map<String, Object>> mgrConfirm(@RequestBody Map<String, Object> payload) {
        return R.ok(attendanceService.batchMgrConfirm(payload));
    }

    @PostMapping("/exceptions/finalize")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "HR 终审锁定（未处理完 C-4.7-01 阻断）")
    public R<Map<String, Object>> finalize(@RequestBody Map<String, Object> payload) {
        return R.ok(attendanceService.hrFinalize(payload));
    }

    @PostMapping("/exceptions/correct")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "更正申请（解锁单员工行）")
    public R<Map<String, Object>> correct(@RequestBody Map<String, Object> payload) {
        return R.ok(attendanceService.correct(payload));
    }

    @GetMapping("/pending-confirmations")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "主管未确认催办清单")
    public R<List<Map<String, Object>>> pendingConfirmations() {
        return R.ok(attendanceService.pendingConfirmations());
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "月度考勤汇总（查询聚合+锁定+挂起标记）")
    public R<List<Map<String, Object>>> summary(@RequestParam String period) {
        return R.ok(attendanceService.monthlySummary(period));
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "手动触发识别（幂等重跑）")
    public R<Map<String, Object>> scan(@RequestParam String date) {
        var out = scanTask.scanForDate(java.time.LocalDate.parse(date));
        return R.ok(Map.of("exceptions", out.exceptions(), "employees", out.employees()));
    }

    // ---------- 补卡 ----------

    @PostMapping("/makeups")
    @Operation(summary = "提交补卡（期限拦截）")
    public R<HrMakeupPunch> submitMakeup(@RequestBody Map<String, Object> payload) {
        return R.ok(makeupService.submit(payload));
    }

    @PostMapping("/makeups/{id}/mgr-confirm")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "主管确认补卡")
    public R<HrMakeupPunch> mgrConfirmMakeup(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        return R.ok(makeupService.mgrConfirm(id, payload));
    }

    @PostMapping("/makeups/{id}/hr-review")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "HR 审核（通过→写 MANUAL 打卡+关闭异常+重识别）")
    public R<HrMakeupPunch> hrReviewMakeup(@PathVariable String id,
                                           @RequestBody Map<String, Object> payload) {
        return R.ok(makeupService.hrReview(id, payload));
    }

    @GetMapping("/makeups")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "补卡清单")
    public R<List<HrMakeupPunch>> makeups(@RequestParam(required = false) String empId,
                                          @RequestParam(required = false) String status) {
        return R.ok(makeupService.list(empId, status));
    }
}

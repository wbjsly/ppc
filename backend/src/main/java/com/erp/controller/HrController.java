package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrOnboarding;
import com.erp.entity.hr.HrPosition;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.hr.HrOrgService;
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

/**
 * 员工档案（M10-1，employee-archive）：部门岗位/建档/维护/提醒。
 */
@Tag(name = "员工档案")
@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class HrController {

    private final HrOrgService orgService;
    private final EmployeeArchiveService archiveService;

    // ---------- 轻量部门/岗位 ----------

    @PostMapping("/depts")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新建部门")
    public R<HrDept> createDept(@RequestBody Map<String, Object> payload) {
        return R.ok(orgService.createDept(payload));
    }

    @GetMapping("/depts")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门列表")
    public R<List<HrDept>> listDepts() {
        return R.ok(orgService.listDepts());
    }

    @PutMapping("/depts/{id}/toggle")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "停用/启用部门")
    public R<HrDept> toggleDept(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(orgService.toggleDept(id, body.get("status")));
    }

    @PostMapping("/positions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新建岗位（角色映射 JSON/编制）")
    public R<HrPosition> createPosition(@RequestBody Map<String, Object> payload) {
        return R.ok(orgService.createPosition(payload));
    }

    @GetMapping("/positions")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "岗位列表")
    public R<List<HrPosition>> listPositions(@RequestParam(required = false) String deptId) {
        return R.ok(orgService.listPositions(deptId));
    }

    @PutMapping("/positions/{id}/toggle")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "停用/启用岗位")
    public R<HrPosition> togglePosition(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(orgService.togglePosition(id, body.get("status")));
    }

    // ---------- 建档六步状态机 ----------

    @PostMapping("/onboardings")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "发起建档（步骤①录用单登记）")
    public R<HrOnboarding> createOnboarding(@RequestBody Map<String, Object> payload) {
        return R.ok(archiveService.createOnboarding(payload));
    }

    @PostMapping("/onboardings/{id}/advance")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "推进当前步骤（携带该步登记数据）")
    public R<HrOnboarding> advance(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(archiveService.advance(id, payload));
    }

    @PostMapping("/onboardings/{id}/return")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "退回上一步")
    public R<HrOnboarding> returnStep(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(archiveService.returnStep(id, body.get("reason")));
    }

    @GetMapping("/onboardings")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "建档单列表")
    public R<List<HrOnboarding>> listOnboarding(@RequestParam(required = false) String status) {
        return R.ok(archiveService.listOnboarding(status));
    }

    @GetMapping("/onboardings/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "建档单详情")
    public R<HrOnboarding> detailOnboarding(@PathVariable String id) {
        return R.ok(archiveService.detailOnboarding(id));
    }

    // ---------- 信息维护 ----------

    @GetMapping("/employees")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "档案列表（部门/岗位/状态/关键字；试用期黄标）")
    public R<List<Map<String, Object>>> listEmployees(@RequestParam(required = false) String deptId,
                                                      @RequestParam(required = false) String posId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String keyword) {
        return R.ok(archiveService.listEmployees(deptId, posId, status, keyword));
    }

    @GetMapping("/employees/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "档案详情（含变更日志/建档单/变动历史）")
    public R<Map<String, Object>> detailEmployee(@PathVariable String id) {
        return R.ok(archiveService.detailEmployee(id));
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "修改主档（BR-07 锁定字段 422；变更日志留痕）")
    public R<HrEmployee> updateEmployee(@PathVariable String id,
                                        @RequestBody Map<String, Object> payload) {
        return R.ok(archiveService.updateEmployee(id, payload));
    }

    @PostMapping("/employees/{id}/lock-change")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "锁定字段特批申请（L2，身份证号/工号）")
    public R<Map<String, Object>> requestLockChange(@PathVariable String id,
                                                    @RequestBody Map<String, String> body) {
        return R.ok(archiveService.requestLockChange(id, body.get("field"),
                body.get("newValue"), body.get("reason")));
    }

    @GetMapping("/employees/{id}/change-logs")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "变更日志")
    public R<List<HrChangeLog>> changeLogs(@PathVariable String id) {
        return R.ok(archiveService.changeLogs(id));
    }

    @GetMapping("/probation-remindings")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "试用期提醒清单（BR-08 ≤15 天，写提醒记录防重）")
    public R<List<Map<String, Object>>> probationRemindings() {
        return R.ok(archiveService.probationRemindings());
    }

    @GetMapping("/rehire-probe")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "二次入职检查（BR-09：身份证命中离职员工）")
    public R<Map<String, Object>> rehireProbe(@RequestParam String idCardNo) {
        return R.ok(archiveService.rehireProbe(idCardNo));
    }
}

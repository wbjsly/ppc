package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrGrade;
import com.erp.entity.hr.HrOrgChange;
import com.erp.entity.hr.HrPosition;
import com.erp.service.hr.HrGradeService;
import com.erp.service.hr.HrOrgChangeService;
import com.erp.service.hr.HrOrgService;
import com.erp.service.hr.HrReportService;
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

/** 组织管理（M10-2，org-structure-management）：职级/部门升级/岗位升级/汇报关系/调整单。 */
@Tag(name = "组织管理")
@RestController
@RequestMapping("/api/hr/org")
@RequiredArgsConstructor
public class HrOrgManagementController {

    private final HrGradeService gradeService;
    private final HrOrgService orgService;
    private final HrReportService reportService;
    private final HrOrgChangeService orgChangeService;

    // ---------- 10.2.4 职级 ----------

    @GetMapping("/grades")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "职级列表")
    public R<List<HrGrade>> grades() {
        return R.ok(gradeService.list());
    }

    @PostMapping("/grades")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新增职级（G+流水/带宽下限<上限/序列 P·M）")
    public R<HrGrade> createGrade(@RequestBody Map<String, Object> payload) {
        return R.ok(gradeService.create(payload));
    }

    @PutMapping("/grades/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "更新职级（带宽变更走 GRADE_BAND 调整单审批）")
    public R<HrGrade> updateGrade(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(gradeService.update(id, payload));
    }

    @PutMapping("/grades/{id}/status")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "停用/启用职级（被岗位引用不可停）")
    public R<HrGrade> toggleGrade(@PathVariable String id, @RequestParam String status) {
        return R.ok(gradeService.toggleStatus(id, status));
    }

    @GetMapping("/band")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "带宽解析（岗位→职级→带宽链）")
    public R<Map<String, Object>> band(@RequestParam String posId) {
        return R.ok(gradeService.resolveBand(posId));
    }

    // ---------- 10.2.1 部门升级 ----------

    @PutMapping("/depts/{id}/deactivate")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门停用（在职员工/启用子部门双阻断）")
    public R<HrDept> deactivateDept(@PathVariable String id) {
        return R.ok(orgService.deactivateDept(id));
    }

    @PutMapping("/depts/{id}/cost-center")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "成本中心锚维护")
    public R<HrDept> setCostCenter(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(orgService.setCostCenter(id, body.get("costCenter")));
    }

    // ---------- 10.2.2 岗位升级 ----------

    @PutMapping("/positions/{id}/meta")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "岗位关联职级 + 说明书要点")
    public R<HrPosition> updatePositionMeta(@PathVariable String id,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(orgService.updatePositionMeta(id,
                payload.get("gradeId") == null ? null : String.valueOf(payload.get("gradeId")),
                payload.get("jdJson") == null ? null : String.valueOf(payload.get("jdJson"))));
    }

    // ---------- 10.2.3 汇报关系 ----------

    @PutMapping("/reports/{empId}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "设置/变更直接汇报人（防环/深度上限/留痕）")
    public R<Map<String, Object>> setReportTo(@PathVariable String empId,
                                              @RequestBody Map<String, Object> payload) {
        String reportTo = payload.get("reportToEmpId") == null ? null
                : String.valueOf(payload.get("reportToEmpId"));
        return R.ok(reportService.setReportTo(empId, reportTo,
                payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
    }

    @GetMapping("/reports/{empId}/ancestors")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "汇报祖先链")
    public R<Map<String, Object>> ancestors(@PathVariable String empId) {
        return R.ok(reportService.ancestors(empId));
    }

    @GetMapping("/reports/{empId}/subordinates")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "直接下属")
    public R<List<Map<String, Object>>> subordinates(@PathVariable String empId) {
        return R.ok(reportService.directReports(empId));
    }

    // ---------- 组织调整单 ----------

    @PostMapping("/changes/merge")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门合并（调整单+审批；级联承接/防重复/非法方向阻断）")
    public R<Map<String, Object>> createMerge(@RequestBody Map<String, Object> payload) {
        return R.ok(orgChangeService.createMerge(payload));
    }

    @PostMapping("/changes/manager")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "部门主管变更（调整单+审批留痕）")
    public R<Map<String, Object>> createManagerChange(@RequestBody Map<String, String> body) {
        return R.ok(orgChangeService.createManagerChange(body.get("deptId"), body.get("managerEmpId")));
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "调整单列表")
    public R<List<HrOrgChange>> changes(@RequestParam(required = false) String chgType,
                                        @RequestParam(required = false) String status) {
        return R.ok(orgChangeService.list(chgType, status));
    }
}

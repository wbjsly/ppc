package com.erp.service.hr;

import com.erp.entity.hr.HrOrgChange;

import java.util.List;
import java.util.Map;

/** 组织调整单（M10-2）：合并/主管变更/带宽变更三型统一走 ApprovalEngine。 */
public interface HrOrgChangeService {

    /**
     * 部门合并（D3 级联承接）：提交调整单走审批。
     * 防重复：源部门存在 APPROVING/EFFECTED 未完成合并单 → 422；
     * 非法方向：源为目标祖先 → L1 阻断。
     */
    Map<String, Object> createMerge(Map<String, Object> payload);

    /** 主管变更调整单（变更部门主管走审批留痕，D3）。 */
    Map<String, Object> createManagerChange(String deptId, String newManagerEmpId);

    /** 带宽变更调整单（HrGradeService 委托创建）。 */
    Map<String, Object> createBandChange(String gradeId, Map<String, Object> after);

    /** 审批通过 → 执行（回调调用；幂等）：合并级联/主管应用/带宽应用。 */
    void execute(String changeId);

    /** 审批拒绝 → 置 REJECTED（回调调用）。 */
    void reject(String changeId);

    List<HrOrgChange> list(String chgType, String status);
}

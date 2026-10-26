package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** HR 轻量部门（add-employee-archive，design D1）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_dept")
public class HrDept extends BaseEntity {
    /** 部门编号 DEPT+流水 */
    private String deptNo;
    private String name;
    private String parentId;
    /** 部门主管（指向 hr_employee.ID，add-org-management） */
    private String managerEmpId;
    /** 主管变更调整单锚（hr_org_change.ID） */
    private String managerApprovalId;
    /** 成本中心锚（双人复核「成本中心变更」要素落点） */
    private String costCenter;
    /** 1 启用 / 0 停用 */
    private String status;
}

package com.erp.service.hr;

import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrPosition;

import java.util.List;
import java.util.Map;

/**
 * HR 轻量部门/岗位主数据（add-employee-archive，design D1）。
 * 不建完整 10.2 组织管理——建档归属与岗位角色映射的最小依赖。
 */
public interface HrOrgService {

    /** 新建部门（编码 DEPT+流水自动生成） */
    HrDept createDept(Map<String, Object> payload);

    /** 部门列表（树形平铺，含状态） */
    List<HrDept> listDepts();

    /** 停用/启用部门 */
    HrDept toggleDept(String deptId, String status);

    /** 新建岗位（编码 POS+流水；角色 JSON 校验；部门须启用） */
    HrPosition createPosition(Map<String, Object> payload);

    /** 岗位列表（按部门筛选） */
    List<HrPosition> listPositions(String deptId);

    /** 停用/启用岗位 */
    HrPosition togglePosition(String posId, String status);

    /** 岗位角色映射（供账号联动；停用岗位返回空并阻断引用） */
    List<String> positionRoles(String posId);

    // ---------- add-org-management（M10-2 原地升级） ----------

    /** 部门停用（双重阻断：在职员工/启用子部门 L1；沿用既有启用岗位校验）。 */
    HrDept deactivateDept(String deptId);

    /** 成本中心锚维护（即时，留痕）。 */
    HrDept setCostCenter(String deptId, String costCenter);

    /** 岗位关联职级 + 岗位说明书要点（即时，留痕）。 */
    HrPosition updatePositionMeta(String posId, String gradeId, String jdJson);
}

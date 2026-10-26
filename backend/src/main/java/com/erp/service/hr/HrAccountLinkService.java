package com.erp.service.hr;

/**
 * 人事账号联动（add-employee-archive design D3，三触点直连）。
 * 失败降级：告警 + 联动待处理标记，人事事务不回滚。
 */
public interface HrAccountLinkService {

    /**
     * 触点①建档完成 → 生成入职开通审批待办（BIZ_TYPE=HrAccountOpen，HR 经理节点）；
     * 审批通过回调 {@link #createAccount(String)} 创建系统账号。
     */
    void onArchived(String empId);

    /**
     * 入职开通审批通过 → 创建系统账号（用户名=工号、初始密码 Init@工号后4位、角色按岗位映射）。
     */
    String createAccount(String empId);

    /**
     * 触点②调岗生效 → 角色换绑（旧岗位角色移除、新岗位角色赋予，留痕）。
     * 返回 null 表示无变更；失败抛异常由调用方降级标记。
     */
    String swapRoles(String empId, String oldPosId, String newPosId);

    /**
     * 触点③离场凭证 → 禁用系统账号。
     */
    void disableAccount(String empId);
}

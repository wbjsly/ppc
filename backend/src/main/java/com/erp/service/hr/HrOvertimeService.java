package com.erp.service.hr;

import com.erp.entity.hr.HrOvertimeApply;

import java.util.List;
import java.util.Map;

/** 加班申请与审批（M10-4-1，overtime-comp-time：36h L1 拦截/紧急补单/主管审批）。 */
public interface HrOvertimeService {

    /**
     * 提交加班申请（事前；紧急补单 1 工作日窗口+主管书面说明；事由必填；
     * 月累计（已申请+已确认）超 OT_MONTHLY_CAP_HOURS：未勾特批 L1 硬阻断，
     * 勾选特批+超限原因 → GM_SPECIAL 进总经理待办（BR-4.7-04））。
     */
    HrOvertimeApply submit(Map<String, Object> payload);

    /**
     * 审批（通过/驳回；GM_SPECIAL 单仅 ROLE_GM 可批，其余状态维持主管审批；
     * opinion 落 APPR_OPINION 留痕）。
     */
    HrOvertimeApply approve(String applyId, Map<String, Object> payload);

    /** 待审批详情（含当月累计小时与部门预算占用率）。 */
    Map<String, Object> approvalView(String applyId);

    /** 申请清单（status 可传 GM_SPECIAL 过滤 GM 待办）。 */
    List<HrOvertimeApply> list(String empId, String status);

    /**
     * 审批超时催办扫描（FR-4.7-6-2）：提交后满 OT_APPROVE_REMIND_DAYS 个工作日
     * 未审批的申请，按部门主管×日聚合写 hr_remind，返回当日催办条数。
     */
    int approvalRemindScan();

    /** 加班审批超时催办清单（最近 30 天，OT_APPROVE_OVERDUE）。 */
    java.util.List<com.erp.entity.hr.HrRemind> approvalReminders();
}

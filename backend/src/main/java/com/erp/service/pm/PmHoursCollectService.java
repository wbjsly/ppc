package com.erp.service.pm;

import com.erp.entity.pm.PmTimesheet;

import java.util.Map;

/** 工时归集（add-timesheet-management，FR-4.8-4-3；审批通过即归集，费率快照）。 */
public interface PmHoursCollectService {

    /** 审批通过时逐行归集（幂等：同 timesheetId 已归集则跳过），返回写入行数 */
    int collect(PmTimesheet sheet);

    /** 四级汇总（任务→包→阶段→项目）+ 预估对账（planHours=WBS estHours 级联） */
    Map<String, Object> summary(String projectId);
}

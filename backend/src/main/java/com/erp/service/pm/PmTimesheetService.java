package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 工时填报与审核（add-timesheet-management，FR-4.8-4-1/2 + BR-4.8-17/18；/my 端 + 11.5.2）。 */
public interface PmTimesheetService {

    /** 保存/更新工时单（仅本人；DRAFT 或 REJECTED 可改；0.5h 步长/24h 合计/任务级校验） */
    Map<String, Object> save(Map<String, Object> payload);

    /** 我的工时单列表（默认本人；PM/PMO/ADMIN 可查他人） */
    List<Map<String, Object>> list(String empId, String dateFrom, String dateTo);

    /** 今日填报状态（/my 页头部：今日单是否存在/状态/合计） */
    Map<String, Object> myToday();

    /** 提交（三道 L1：任务级校验/24h 合计/补录分档；BR-4.8-17 两档+更早 422） */
    Map<String, Object> submit(String id);

    /** 审核（审核人校验；pass→APPROVED+归集；reject 必填原因→REJECTED） */
    Map<String, Object> review(String id, Map<String, Object> payload);

    /** 批量通过（逐单走审核人校验，返回成功/失败计数） */
    Map<String, Object> bulkApprove(List<String> ids);

    /** 审核人解析：单任务负责人/跨人或无负责人回落项目经理（11.5.2 待办队列用） */
    List<Map<String, Object>> pendingReview();
}

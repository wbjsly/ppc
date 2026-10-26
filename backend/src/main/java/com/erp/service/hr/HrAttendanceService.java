package com.erp.service.hr;

import com.erp.entity.hr.HrAttendanceException;

import java.util.List;
import java.util.Map;

/** 考勤异常状态机与批量操作（M10-3-3/4/5，attendance-core D3）。 */
public interface HrAttendanceService {

    /** 异常清单（按状态/员工/日期范围筛选）。 */
    List<HrAttendanceException> list(String status, String empId, String fromDate, String toDate);

    /**
     * 批量申诉（5 工作日期限拦截 ATTEND_APPEAL_DAYS；佐证引用/文字说明）。
     * payload: {ids:[...], reason, evidenceRef}
     */
    Map<String, Object> batchAppeal(Map<String, Object> payload);

    /** 佐证无效退回（主管判定；状态回退 IDENTIFIED 注明原因）。 */
    Map<String, Object> returnAppeal(Map<String, Object> payload);

    /**
     * 批量主管确认（通过→MGR_CONFIRMED / 驳回→回退 IDENTIFIED；校验部门主管或 HR 代确认留痕）。
     * payload: {approveIds:[...], rejectIds:[{id,opinion}], opinion}
     */
    Map<String, Object> batchMgrConfirm(Map<String, Object> payload);

    /**
     * HR 终审（全量锁定 HR_FINAL；生成月度锁）。
     * payload: {period}
     */
    Map<String, Object> hrFinalize(Map<String, Object> payload);

    /**
     * 更正申请（终审后发现错误：解锁单员工行 + 异常转 CORRECTED；HR 经理审批留痕）。
     * payload: {exceptionId, reason}
     */
    Map<String, Object> correct(Map<String, Object> payload);

    /** 主管未确认催办清单（截止日 ≤ MGR_CONFIRM_DEADLINE_DAYS）。 */
    List<Map<String, Object>> pendingConfirmations();

    /** 我的异常（员工自助）。 */
    List<HrAttendanceException> myExceptions(String empId, String period);

    /**
     * 月度考勤汇总（D6 查询聚合：异常分型计数 + 请假分型天数与扣款 + 加班分型时长与工资小计；
     * 含锁定状态与 C-4.7-01 PENDING 挂起标记——10.5 薪酬读「已锁+无挂起」行）。
     */
    List<Map<String, Object>> monthlySummary(String period);
}

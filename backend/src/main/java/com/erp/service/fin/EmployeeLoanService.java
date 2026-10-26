package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 员工借款（M8-8-2，spec expense-reimbursement，FR-4.6-7-1/2、BR-4.6-02、C-4.6-14）。
 */
public interface EmployeeLoanService {

    /**
     * 提交借款申请：实时判定该员工是否存在账龄 > LOAN_OVERDUE_DAYS(默认90) 的未核销借款，
     * 存在 → 审批链自动加签财务主管（C-4.6-14 双签解冻，判定快照进审批标题留痕，
     * design D4：判定输入输出进审批日志，事后还清不改变该申请的链）。
     */
    Map<String, Object> apply(Map<String, Object> payload);

    /** 借款单列表（可按员工/状态过滤） */
    List<Map<String, Object>> list(String employeeId, String status);

    /** 借款单详情（含冲销明细回看） */
    Map<String, Object> detail(String id);

    /**
     * 账龄监控视图（8.8.2，BR-4.6-02 升级提示）：按员工聚合
     * 未核销笔数/合计/最大账龄，> 阈值红标「需财务主管处理」。实时计算不落库。
     */
    List<Map<String, Object>> agingView();

    /**
     * 供审批提交复用的实时超期判定：返回该员工超期天数（未超期或无未核销返回 0）。
     */
    int overdueDaysOf(String employeeId);
}

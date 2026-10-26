package com.erp.service.hr;

import com.erp.entity.hr.HrPayCorrection;
import com.erp.entity.hr.HrPayAdjustment;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;

import java.util.List;
import java.util.Map;

/** 薪资核算与 PayrollRun（payroll-calculation 10.5.3/10.5.4：补扣项/核算/批次/审批/更正/工资条）。 */
public interface HrPayrollRunService {

    // ---------- 补扣项（核算期内可改，批次生成后锁） ----------

    HrPayAdjustment saveAdjustment(Map<String, Object> payload);

    List<HrPayAdjustment> adjustmentList(String period, String empId);

    // ---------- 核算与批次 ----------

    /**
     * 数据准备 + 核算 + 生成 PayrollRun（DRAFT）：
     * 考勤汇总锁定取数（挂起员工暂停）+ 绩效结果快照（无结果按 1.0 标注）+ 补扣项 + 社保 + 个税；
     * 实发负数 → L1 标记异常（NEGATIVE item）；环比>20% → VOLATILE 标记（L4 待确认）。
     */
    HrPayrollRun generate(String period, Map<String, Object> payload);

    /** 核算预览（不落库，供复核页）。 */
    Map<String, Object> preview(String period);

    /** 提交审批（DRAFT→SUBMITTING；存在未处理 NEGATIVE/VOLATILE 阻断）。 */
    HrPayrollRun submit(String runId, Map<String, Object> payload);

    /** HR 经理审批（通过→APPROVED 锁定+触发工资条/计提凭证；驳回→REJECTED 可修正重提）。 */
    HrPayrollRun approve(String runId, Map<String, Object> payload);

    /** 作废重建（生成后不可改明细，BR-4.7-13）。 */
    HrPayrollRun voidRun(String runId, String reason);

    HrPayrollRun runById(String runId);

    List<HrPayrollRun> runList(String period, String status);

    List<HrPayrollRunItem> runItems(String runId);

    // ---------- 薪酬更正（L2） ----------

    HrPayCorrection submitCorrection(Map<String, Object> payload);

    HrPayCorrection approveCorrection(String corrId, Map<String, Object> payload);

    List<HrPayCorrection> correctionList(String period, String runId);

    // ---------- 工资条（审批过推送自助端） ----------

    List<Map<String, Object>> myPayslips(String empId);

    Map<String, Object> confirmPayslip(String payslipId, String empId);

    Map<String, Object> disputePayslip(String payslipId, String empId, String content);

    /** 核算员回复异议（3 工作日，留痕）。 */
    Map<String, Object> replyDispute(String payslipId, String content);
}

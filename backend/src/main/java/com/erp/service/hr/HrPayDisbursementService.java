package com.erp.service.hr;

import com.erp.entity.hr.HrPayDiff;
import com.erp.entity.hr.HrPayDisbursement;
import com.erp.entity.hr.HrPayExecution;
import com.erp.entity.hr.HrPayReceipt;

import java.util.List;
import java.util.Map;

/** 薪酬发放与对账（payroll-disbursement M10-6：代发文件/发放登记/回单对账/差异/归档）。 */
public interface HrPayDisbursementService {

    /**
     * 生成代发文件（已审批 NORMAL/CORRECTION 批次）：姓名/卡号/实发，CSV 模板可配；
     * 缺 BANK_CARD_NO 阻断该员工入文件（列清单）；合计=批次实发校验；重复生成须先作废。
     */
    HrPayDisbursement generateFile(String runId, Map<String, Object> payload);

    /** 作废旧代发文件（留痕，允许重新生成）。 */
    HrPayDisbursement voidFile(String disbId, String reason);

    List<HrPayDisbursement> fileList(String period, String runId);

    /**
     * 出纳登记发放执行：实付合计须=文件合计（不平须备注）；>5万 L4 大额提示；
     * 全部成功→批次 PAID；有失败→PARTIAL + 失败笔进差异清单。
     */
    HrPayExecution registerExecution(Map<String, Object> payload);

    List<HrPayExecution> executionList(String runId);

    /** 回单登记（手工；自动获取留桩）。 */
    HrPayReceipt registerReceipt(Map<String, Object> payload);

    /**
     * 逐笔对账：回单与代发明细金额+账号精确匹配优先；全部匹配且金额一致 → 批次 RECONCILED；
     * 不一致/未匹配进差异清单（超容差 L4）。
     */
    Map<String, Object> reconcile(String runId);

    /** 差异清单。 */
    List<HrPayDiff> diffList(String runId, String status);

    /**
     * 差异处理：方案 RESEND/NEXT_MONTH/MANUAL；金额调整须 HR 经理审批（L2）；
     * 5 工作日时限；全部差异关闭后可归档。
     */
    HrPayDiff resolveDiff(String diffId, Map<String, Object> payload);

    /** HR 经理 L2 审批差异金额调整方案。 */
    HrPayDiff approveDiff(String diffId, Map<String, Object> payload);

    /**
     * 归档：RECONCILED + 差异全部关闭 → 凭证包快照 ARCHIVED 不可改；
     * 归档后修改尝试 422。
     */
    Map<String, Object> archive(String runId);

    /** 对账进度看板（匹配率/差异清单）。 */
    Map<String, Object> reconBoard(String runId);
}

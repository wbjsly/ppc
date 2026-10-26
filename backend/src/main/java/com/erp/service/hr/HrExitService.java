package com.erp.service.hr;

import com.erp.entity.hr.HrExit;

import java.util.List;
import java.util.Map;

/**
 * 离职管理（employee-transfer，C-4.7-04 交接硬阻断，离场凭证）。
 */
public interface HrExitService {

    /**
     * 提交离职申请：通知期校验（正式 30 天/试用 3 天，SysParam）——
     * 未满 L4 提示留痕不阻断（NOTICE_OK=0）。
     */
    HrExit apply(Map<String, Object> payload);

    /** HR 确认进入交接阶段（审批链走 ApprovalEngine，主管→部门负责人→HR） */
    HrExit startApproval(String exitId);

    /**
     * 交接清单（C-4.7-04）：①资产归还——EAM 卡片按 HOLDER_EMP_NO 列示，
     * 逐张确认回写清空；②权限移交——系统账号确认禁用；③④文档/在办手工登记+接收人。
     * 返回四类完成状态与未完成项。
     */
    Map<String, Object> handoverChecklist(String exitId);

    /** 确认资产归还（回写 EAM 卡片持有人清空） */
    HrExit confirmAssetReturn(String exitId, List<String> cardIds);

    /** 确认权限移交（禁用系统账号） */
    HrExit confirmAccountHandover(String exitId);

    /** 登记知识文档/在办工作交接项（含接收人） */
    HrExit registerHandoverItems(String exitId, String category, List<Map<String, Object>> items);

    /** 薪酬结算登记（四金额离线登记） + 社保减员留桩 → SETTLED */
    HrExit settle(String exitId, Map<String, Object> payload);

    /**
     * 生成离场凭证（C-4.7-04 L1：四类交接未齐硬阻断）：
     * 汇总四项确认，置主档已离职 + 禁用账号。
     */
    HrExit issueExitCert(String exitId);

    List<HrExit> list(String status);
    Map<String, Object> detail(String exitId);

    /** 挽留意见（审批人填写，非阻断留痕，FR-4.7-4-12） */
    HrExit retainNote(String exitId, String note);

    /** 社保减员手工豁免（无 DONE 减员单时放行离场凭证，记录豁免人与原因，FR-4.7-4-15） */
    HrExit waiveReduce(String exitId, String reason);

    /** 我的离职单（员工自助端 /api/my/exit） */
    List<HrExit> listByEmp(String empId);
}

package com.erp.service.hr;

import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrOnboarding;

import java.util.List;
import java.util.Map;

/**
 * 员工档案（employee-archive，§4.7 流程一）：
 * 建档六步状态机 / 主档维护与变更日志 / 锁定字段 L2 特批 / 试用期提醒 / 二次入职。
 */
public interface EmployeeArchiveService {

    // ---------- 部门岗位见 HrOrgService ----------

    // ---------- 建档六步状态机（FR-4.7-1-1~6） ----------

    /** 发起建档（步骤①：登记录用审批单，OFFER_APPROVED 门禁） */
    HrOnboarding createOnboarding(Map<String, Object> payload);

    /**
     * 推进当前步骤（携带该步登记数据）：
     * ①录用单（须已审批通过）→ ②信息登记（实名/四要素人工核验留桩）→
     * ③证件核验（离职证明必备 L1）→ ④合同签订（试用期合规 L1）→
     * ⑤生成主档（工号 EMP+流水、身份证唯一 L1、二次入职引导）→ ⑥社保增员留桩 → DONE
     */
    HrOnboarding advance(String onbId, Map<String, Object> payload);

    /** 退回上一步（留痕原因） */
    HrOnboarding returnStep(String onbId, String reason);

    /** 建档单列表/详情 */
    List<HrOnboarding> listOnboarding(String status);
    HrOnboarding detailOnboarding(String onbId);

    // ---------- 信息维护 ----------

    /**
     * 修改主档字段（BR-4.7-07）：身份证号/工号直改 422（须走特批）；
     * 其余字段变更写 hr_change_log（前值/后值/人/时间/原因必填）。
     */
    HrEmployee updateEmployee(String empId, Map<String, Object> payload);

    /** 锁定字段（身份证号/工号）特批申请（L2，ApprovalEngine） */
    Map<String, Object> requestLockChange(String empId, String field, String newValue, String reason);

    /** 变更日志列表 */
    List<HrChangeLog> changeLogs(String empId);

    // ---------- 查询与提醒 ----------

    /** 档案列表（部门/岗位/状态/关键字） */
    List<Map<String, Object>> listEmployees(String deptId, String posId, String status, String keyword);

    /** 档案详情（含建档单与变动/离职历史锚） */
    Map<String, Object> detailEmployee(String empId);

    /** 试用期提醒清单（BR-4.7-08：≤ HR_PROBATION_REMIND_DAYS 天）+ 写提醒记录（同日防重） */
    List<Map<String, Object>> probationRemindings();

    /** 二次入职检查（BR-4.09）：身份证命中返回原档案信息供「重新入职」入口 */
    Map<String, Object> rehireProbe(String idCardNo);
}

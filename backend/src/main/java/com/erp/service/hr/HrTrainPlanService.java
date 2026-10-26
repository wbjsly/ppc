package com.erp.service.hr;

import java.util.List;
import java.util.Map;

/**
 * 年度培训计划（training-plan-execution）：编制即发布、任务展开（UK 幂等）、
 * 自助确认、HR 结果登记与完成判定、完成率统计、催办、费用计提凭证。
 */
public interface HrTrainPlanService {

    /** 计划清单（年度过滤，含任务统计） */
    List<Map<String, Object>> list(Integer year);

    /** 新建计划（编制即发布：自动计划号 + 按对象展开参训任务） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 修改计划（变更留痕；对象变更只补新任务不删旧任务） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 作废计划（未完成任务连带 CANCELLED；已生成凭证红字冲销） */
    void cancel(String id);

    /** 计划详情（计划 + 任务明细 + 完成率） */
    Map<String, Object> detail(String id);

    /** HR 结果登记（成绩≥合格线 COMPLETED 否则 FAILED；修改留痕；矩阵联动） */
    Map<String, Object> registerResult(String taskId, Map<String, Object> payload);

    /** 实际费用登记（自动计提凭证 TRAINING 锚点幂等；金额变动红冲重开） */
    Map<String, Object> registerCost(String planId, Map<String, Object> payload);

    /** 完成率：计划行/部门/年度三维度（CANCELLED 不计分母） */
    List<Map<String, Object>> rates(Integer year, String deptId);

    /** 催办扫描：临期/逾期未完成任务（hr_remind + 站内推送，幂等） */
    Map<String, Object> remindScan();

    /** 我的培训任务（含计划信息） */
    List<Map<String, Object>> myTasks(String empId);

    /** 员工自助确认（PENDING→CONFIRMED，来源 SELF；完成判定仍以 HR 登记为准） */
    Map<String, Object> confirmTask(String taskId, String empId);
}

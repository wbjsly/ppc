package com.erp.service.hr;

import com.erp.entity.hr.HrPerfPlan;
import com.erp.entity.hr.HrPerfResult;
import com.erp.entity.hr.HrPerfScore;

import java.util.List;
import java.util.Map;

/** 绩效管理（performance-management，菜单 10.10）：方案/评分/校准/结果。 */
public interface HrPerfService {

    /** 创建方案（周期+等级定义；同周期同部门唯一生效；评分期内系数锁定）。 */
    HrPerfPlan createPlan(Map<String, Object> payload);

    /** 作废重建（仅 DRAFT/ACTIVE 且无评分的方案可作废）。 */
    HrPerfPlan voidPlan(String planId);

    /** 方案清单（period/deptId/status 过滤）。 */
    List<HrPerfPlan> planList(String period, String deptId, String status);

    /** 录入评分（分值→等级按方案区间自动映射，也允许直接指定；周期关闭锁定）。 */
    HrPerfScore enterScore(Map<String, Object> payload);

    /** 提交评分 DRAFT→SUBMITTED（留痕）。 */
    HrPerfScore submitScore(String scoreId);

    /** 批量提交（勾选多条）。 */
    Map<String, Object> batchSubmit(List<String> scoreIds);

    /** 评分清单（period/planId/empId/status 过滤）。 */
    List<HrPerfScore> scoreList(String period, String planId, String empId, String status);

    /**
     * 批量校准流转：SUBMITTED→CALIBRATING→CALIBRATED（action）。
     * payload: scoreIds[], action=CALIBRATE|CONFIRM|REJECT, toGrade（可选，等级调整）, reason（调整/驳回必填）
     */
    Map<String, Object> calibrate(Map<String, Object> payload);

    /** 确认 CALIBRATED→CONFIRMED（批量，生成结果快照）。 */
    Map<String, Object> confirm(Map<String, Object> payload);

    /** 结果快照清单（薪酬取数来源）。 */
    List<HrPerfResult> resultList(String period, String deptId);

    /** 校准日志（scoreId 过滤）。 */
    List<Map<String, Object>> calibrateLogs(String scoreId);
}

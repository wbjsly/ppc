package com.erp.service.hr;

import com.erp.entity.hr.HrGrade;

import java.util.List;
import java.util.Map;

/** HR 职级（M10-2-4）：CRUD + 带宽变更走调整单审批（C-4.7-06 同口径）。 */
public interface HrGradeService {

    /** 新增职级（编码自动生成 G+流水；下限<上限校验；序列 P/M 校验）。 */
    HrGrade create(Map<String, Object> payload);

    /** 更新职级（带宽变更 → 生成 GRADE_BAND 调整单走审批；其余字段直接更新留痕）。 */
    HrGrade update(String gradeId, Map<String, Object> payload);

    /** 停用/启用（被岗位引用的停用 → 422）。 */
    HrGrade toggleStatus(String gradeId, String status);

    List<HrGrade> list();

    /** 带宽解析（岗位→职级→带宽链，add-org-management D4）。 */
    Map<String, Object> resolveBand(String posId);
}

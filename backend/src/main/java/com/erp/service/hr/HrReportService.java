package com.erp.service.hr;

import java.util.List;
import java.util.Map;

/** 员工直接汇报关系（M10-2-3，两层模型员工层；防环/深度上限，变更留痕）。 */
public interface HrReportService {

    /** 设置/变更员工直接汇报人（自汇报 422/环路 422/深度超限 422；变更写 hr_change_log）。 */
    Map<String, Object> setReportTo(String empId, String reportToEmpId, String reason);

    /** 祖先链（empId 沿汇报线上溯，含深度）。 */
    Map<String, Object> ancestors(String empId);

    /** 直接下属列表。 */
    List<Map<String, Object>> directReports(String empId);
}

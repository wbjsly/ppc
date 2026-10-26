package com.erp.service.hr;

import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** HR 班次与排班日历（M10-3-1，attendance-core）。 */
public interface HrShiftService {

    /** 新增班次（编码 SHIFT+流水自动生成；跨夜班工时计算）。 */
    HrShift create(Map<String, Object> payload);

    /** 更新班次。 */
    HrShift update(String shiftId, Map<String, Object> payload);

    /** 停用/启用。 */
    HrShift toggleStatus(String shiftId, String status);

    List<HrShift> list();

    // ---------- 排班日历 ----------

    /** 单条排班（班次或休息；同人同日重排以最后一次为准 L4 留痕）。 */
    HrSchedule schedule(Map<String, Object> payload);

    /** 部门批量默认班次（日期范围内全员）。 */
    Map<String, Object> bulkSchedule(Map<String, Object> payload);

    /** 复制上月排班至本月（同日已有排班 L4 提示并覆盖）。 */
    Map<String, Object> copyMonth(String fromPeriod, String toPeriod);

    /** 员工某月排班列表。 */
    List<HrSchedule> monthSchedules(String empId, String period);

    /** 部门某月排班（矩阵：员工×日期）。 */
    List<Map<String, Object>> deptMonthMatrix(String deptId, String period);
}

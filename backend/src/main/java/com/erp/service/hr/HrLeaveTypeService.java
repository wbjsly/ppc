package com.erp.service.hr;

import com.erp.entity.hr.HrLeaveType;

import java.util.List;
import java.util.Map;

/** 假期类型字典（M10.4.4，leave-management：扣减系数/法定天数/计薪标志参数化）。 */
public interface HrLeaveTypeService {

    /** 启用类型列表（申请表单驱动）。 */
    List<HrLeaveType> list();

    /** 更新类型规则（扣减系数/法定天数/计薪标志——法定天数同时写 LEAVE_STATUTORY_DAYS 参数留痕）。 */
    HrLeaveType update(String typeCode, Map<String, Object> payload);

    /** 按编码取类型（含规则）。 */
    HrLeaveType requireByCode(String typeCode);
}

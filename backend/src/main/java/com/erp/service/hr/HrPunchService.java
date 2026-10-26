package com.erp.service.hr;

import com.erp.entity.hr.HrPunchRecord;

import java.util.List;
import java.util.Map;

/** 打卡采集三通道（M10-3-2，attendance-core：网页/H5 定位/CSV 导入）。 */
public interface HrPunchService {

    /** 网页今日打卡（同向重复拦截）。 */
    HrPunchRecord webPunch(Map<String, Object> payload);

    /** H5 定位打卡（经纬度落库+超阈值 SUSPICIOUS）。 */
    HrPunchRecord gpsPunch(Map<String, Object> payload);

    /**
     * CSV 批量导入（考勤机兼容：工号/日期/时间/方向；按工号关联、同时刻去重、报错清单）。
     * rows: [{empNo, punchDate, punchTime, dir}]
     */
    Map<String, Object> importCsv(List<Map<String, Object>> rows);

    /** 我的打卡记录（按月）。 */
    List<HrPunchRecord> myRecords(String empId, String period);

    /** 导入批次明细。 */
    List<HrPunchRecord> batchRecords(String batchId);
}

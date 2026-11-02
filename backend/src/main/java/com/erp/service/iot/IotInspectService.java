package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 设备巡检执行（13.3，spec iot-mobile S-4.11-06 / FR-4.11-3-x 前置）。
 * 任务源：QMS 巡检计划（erp_qms_patrol_plan 到期批量生成）或手动指定；
 * 扫码签到 → 逐项点检 → 异常标记（联动异常上报）→ 拍照留证。
 */
public interface IotInspectService {

    /** 巡检任务批量生成（ACTIVE 计划且 NEXT_RUN_TIME 到期 → 生成执行记录） */
    Map<String, Object> generateFromPlans();

    /** 手动创建巡检任务（设备 + 点检项模板） */
    Map<String, Object> create(Map<String, Object> body);

    /** 扫码设备签到（CHECKIN_AT/BY） */
    Map<String, Object> checkin(String id, String deviceNo);

    /**
     * 逐点检项录入与提交：itemsJson=[{item,value,unit,abnormal,remark}]。
     * 异常项（abnormal=1）须拍照留证（photoUrls 空 → 422 补拍提示）；
     * 提交时自动生成异常上报单（EQUIPMENT 类型）并回填 EXCEPTION_ID。
     */
    Map<String, Object> submit(String id, String itemsJson, String photoUrls);

    /** 巡检记录查询（设备/状态筛选） */
    List<Map<String, Object>> list(String deviceNo, String status);

    /** 超时漏检提醒扫描（OPEN 且 DUE_AT 过期 → OVERDUE_FLAG=1） */
    Map<String, Object> scanOverdue();
}

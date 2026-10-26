package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 工时偏差与预警（add-timesheet-management，FR-4.8-4-4/5 + BR-4.8-19 + C-4.8-03）。 */
public interface PmHoursVarianceService {

    /** 偏差树（项目→阶段→包→任务：plan/actual/deviation）+ 预警列表 */
    Map<String, Object> variance(String projectId);

    /** 预警「已处理」标记（PM/PMO；可附说明；幂等） */
    Map<String, Object> handleAlert(String alertId, String note);

    /**
     * 每日扫描（04:00 任务调用）：
     * 1) 偏差分级预警（>YELLOW 通知 PM / >RED 通知 PM+PMO，项目+级别幂等）
     * 2) 超 5 工作日未处理 → 升级通知 PMO + hr_remind（幂等标记）
     * 返回处理计数。
     */
    Map<String, Object> dailyScan();
}
